"""`_StructuredLLM` Redis 캐시 검증 (#623 — LLM structured 응답 캐시 + 캐시 주기 purge).

배경: HF LLM(Qwen3-8B) 호출이 개인 HF 토큰을 팀 공용으로 소모한다. 일반 `.invoke()`는 이미
`CachedLLM.invoke`에서 Redis 24h 캐시가 적용되지만, structured output 경로(`_StructuredLLM`,
OCR 교정 등)는 캐시가 없어 매번 신규 호출됐다 — 이 파일은 그 캐시 로직만 검증한다.

- raw 응답 문자열만 캐시하고, 캐시 히트여도 `PydanticOutputParser.parse()`는 매번 수행
- 캐시 키에 스키마명이 포함돼 같은 프롬프트라도 스키마가 다르면 캐시가 분리됨
- LLM 호출 실패·파싱 실패 시 캐시에 저장하지 않음
- ttl 파라미터가 `setex`에 그대로 반영됨(OCR/report/briefing 등 짧은 TTL 훅)
- Redis 조회/저장 오류가 응답 자체를 막지 않음(캐시 미스/미저장으로 폴백)
- 캐시 히트값이 현재 스키마와 안 맞는 stale 값이면(예: 스키마 변경 배포 후) parse 예외를 삼키고
  LLM 재호출로 폴백 + 캐시 갱신(#623 P2 픽스 — 무방비였던 캐시 히트 파싱이 500 크래시를 내던 문제)
"""
from unittest.mock import MagicMock, patch

import pytest
import redis
from pydantic import BaseModel

from ai.core.llm_client import CACHE_TTL_SECONDS, CachedLLM, _StructuredLLM


class _FakeRedis:
    """get/setex/scan_iter/delete만 지원하는 최소 인메모리 페이크(테스트 전용, 실제 redis-py 미사용)."""

    def __init__(self):
        self.store: dict[str, str] = {}
        self.ttls: dict[str, int] = {}
        self.counters: dict[str, int] = {}

    def get(self, key):
        return self.store.get(key)

    def incrby(self, key, amount):
        # _log_usage()가 사용량 집계에 쓰는 메서드 — 캐시 테스트에서는 부수효과만 흡수.
        self.counters[key] = self.counters.get(key, 0) + amount

    def setex(self, key, ttl, value):
        self.store[key] = value
        self.ttls[key] = ttl

    def scan_iter(self, match=None):
        if match is None:
            yield from list(self.store.keys())
            return
        prefix = match.rstrip("*")
        for key in list(self.store.keys()):
            if key.startswith(prefix):
                yield key

    def delete(self, *keys):
        for k in keys:
            self.store.pop(k, None)
            self.ttls.pop(k, None)


class _Schema(BaseModel):
    field1: str


class _OtherSchema(BaseModel):
    field2: str


def _make_response(content: str):
    resp = MagicMock()
    resp.content = content
    resp.usage_metadata = {"total_tokens": 42}
    return resp


@patch("ai.core.llm_client._redis")
def test_structured_cache_hit_skips_llm_call_but_parses_every_time(mock_redis_factory):
    fake = _FakeRedis()
    mock_redis_factory.return_value = fake

    chat = MagicMock()
    chat.invoke.return_value = _make_response('{"field1": "hello"}')

    structured = _StructuredLLM(chat, _Schema, cache=True, cache_namespace="ns")

    first = structured.invoke("prompt")
    second = structured.invoke("prompt")

    assert chat.invoke.call_count == 1  # 2번째는 캐시 히트라 LLM 미호출
    assert first == second == _Schema(field1="hello")


@patch("ai.core.llm_client._redis")
def test_structured_cache_key_includes_schema_name(mock_redis_factory):
    """같은 프롬프트라도 스키마가 다르면 캐시 키가 분리돼 둘 다 실제 LLM 호출을 거친다."""
    fake = _FakeRedis()
    mock_redis_factory.return_value = fake

    chat = MagicMock()
    chat.invoke.side_effect = [
        _make_response('{"field1": "a"}'),
        _make_response('{"field2": "b"}'),
    ]

    s1 = _StructuredLLM(chat, _Schema, cache=True, cache_namespace="ns")
    s2 = _StructuredLLM(chat, _OtherSchema, cache=True, cache_namespace="ns")

    s1.invoke("same prompt")
    s2.invoke("same prompt")

    assert chat.invoke.call_count == 2
    assert len(fake.store) == 2


@patch("ai.core.llm_client._redis")
def test_structured_cache_not_stored_on_llm_call_failure(mock_redis_factory):
    fake = _FakeRedis()
    mock_redis_factory.return_value = fake

    chat = MagicMock()
    chat.invoke.side_effect = RuntimeError("HF 호출 실패")

    structured = _StructuredLLM(chat, _Schema, cache=True, cache_namespace="ns")

    with pytest.raises(RuntimeError):
        structured.invoke("prompt")

    assert fake.store == {}


@patch("ai.core.llm_client._redis")
def test_structured_cache_not_stored_on_parse_failure(mock_redis_factory):
    fake = _FakeRedis()
    mock_redis_factory.return_value = fake

    chat = MagicMock()
    chat.invoke.return_value = _make_response("not-json-at-all")  # 매 재시도마다 파싱 실패

    structured = _StructuredLLM(chat, _Schema, cache=True, cache_namespace="ns")

    with pytest.raises(Exception):  # noqa: B017 — PydanticOutputParser가 던지는 예외 타입은 버전에 따라 다를 수 있음
        structured.invoke("prompt")

    assert fake.store == {}


@patch("ai.core.llm_client._redis")
def test_structured_cache_ttl_hook_for_short_lived_namespaces(mock_redis_factory):
    """OCR 등 개인정보 섞인 응답이 짧은 TTL(예: 1h)로 setex되는지 확인(#623 요구사항)."""
    fake = _FakeRedis()
    mock_redis_factory.return_value = fake

    chat = MagicMock()
    chat.invoke.return_value = _make_response('{"field1": "x"}')

    structured = _StructuredLLM(chat, _Schema, cache=True, cache_namespace="ocr", ttl=3600)
    structured.invoke("prompt")

    assert list(fake.ttls.values()) == [3600]


@patch("ai.core.llm_client._redis")
def test_cached_llm_with_structured_output_propagates_cache_namespace_and_ttl(mock_redis_factory):
    """CachedLLM.with_structured_output()이 cache 플래그·namespace를 물려주고 ttl 훅을 지원."""
    fake = _FakeRedis()
    mock_redis_factory.return_value = fake

    chat = MagicMock()
    chat.invoke.return_value = _make_response('{"field1": "y"}')

    cached_llm = CachedLLM(chat, cache=True, cache_namespace="hf:model:0.1")
    structured = cached_llm.with_structured_output(_Schema, ttl=1800)

    assert structured._cache is True
    assert structured._cache_namespace == "hf:model:0.1"
    assert structured._ttl == 1800

    structured.invoke("prompt")
    assert any(key.startswith("ai:cache:hf:model:0.1:_Schema:") for key in fake.store)


def test_structured_cache_default_ttl_when_unspecified():
    chat = MagicMock()
    cached_llm = CachedLLM(chat, cache=True, cache_namespace="ns")
    structured = cached_llm.with_structured_output(_Schema)

    assert structured._ttl == CACHE_TTL_SECONDS


@patch("ai.core.llm_client._redis")
def test_structured_cache_read_error_falls_back_to_llm_call(mock_redis_factory):
    """redis.get이 RedisError를 던져도 캐시 미스로 취급해 정상 호출한다(가용성 우선, 캐시 부가기능)."""

    class _BrokenRedis(_FakeRedis):
        def get(self, key):
            raise redis.RedisError("연결 실패")

    fake = _BrokenRedis()
    mock_redis_factory.return_value = fake

    chat = MagicMock()
    chat.invoke.return_value = _make_response('{"field1": "z"}')

    structured = _StructuredLLM(chat, _Schema, cache=True, cache_namespace="ns")
    result = structured.invoke("prompt")

    assert result == _Schema(field1="z")
    chat.invoke.assert_called_once()


@patch("ai.core.llm_client._redis")
def test_structured_cache_write_error_does_not_fail_response(mock_redis_factory):
    """redis.setex이 RedisError를 던져도 정상 파싱 결과는 그대로 반환한다."""

    class _WriteBrokenRedis(_FakeRedis):
        def setex(self, key, ttl, value):
            raise redis.RedisError("쓰기 실패")

    fake = _WriteBrokenRedis()
    mock_redis_factory.return_value = fake

    chat = MagicMock()
    chat.invoke.return_value = _make_response('{"field1": "w"}')

    structured = _StructuredLLM(chat, _Schema, cache=True, cache_namespace="ns")
    result = structured.invoke("prompt")

    assert result == _Schema(field1="w")
    assert fake.store == {}


@patch("ai.core.llm_client._redis")
def test_structured_cache_disabled_never_touches_redis(mock_redis_factory):
    """cache=False면 조회·저장 모두 건너뛴다(기존 CachedLLM.invoke(cache=False) 패턴과 동일)."""
    fake = _FakeRedis()
    mock_redis_factory.return_value = fake

    chat = MagicMock()
    chat.invoke.return_value = _make_response('{"field1": "v"}')

    structured = _StructuredLLM(chat, _Schema, cache=False, cache_namespace="ns")
    structured.invoke("prompt")
    structured.invoke("prompt")

    assert chat.invoke.call_count == 2  # 캐시 비활성 — 매번 실제 호출
    assert fake.store == {}


@patch("ai.core.llm_client._redis")
def test_structured_cache_stale_value_falls_back_to_llm_call_and_refreshes(mock_redis_factory):
    """캐시에 현재 스키마와 맞지 않는 stale 값(예: 스키마 변경 배포 후 남은 옛 캐시)이 있으면,
    parse() 예외를 삼키고 캐시 미스와 동일하게 LLM을 재호출해 정상 결과를 반환한다(#623 P2 픽스).

    수정 전에는 캐시 히트 경로의 parse()가 무방비라 여기서 예외가 그대로 전파돼 500 크래시였다.
    손상된 키는 delete로 정리되고, 재호출 성공 시 새 값으로 다시 setex되어 캐시가 갱신된다.
    """
    fake = _FakeRedis()
    mock_redis_factory.return_value = fake

    structured = _StructuredLLM(MagicMock(), _Schema, cache=True, cache_namespace="ns")
    cache_key = structured._cache_key(f"prompt\n\n{structured._parser.get_format_instructions()}")

    # 현재 스키마(_Schema: field1)와 맞지 않는 stale 값을 캐시에 직접 심어둔다(예: 예전 스키마 응답).
    fake.store[cache_key] = '{"field1_old_name": "stale"}'

    chat = MagicMock()
    chat.invoke.return_value = _make_response('{"field1": "fresh"}')
    structured._chat = chat

    result = structured.invoke("prompt")

    assert result == _Schema(field1="fresh")
    chat.invoke.assert_called_once()  # stale 캐시를 버리고 실제로 재호출했다
    assert fake.store[cache_key] == '{"field1": "fresh"}'  # 캐시가 새 값으로 갱신됨(delete 후 재-setex)


@patch("ai.core.llm_client._redis")
def test_structured_cache_stale_value_survives_delete_failure(mock_redis_factory):
    """stale 캐시 정리(delete)가 redis 오류로 실패해도, LLM 재호출·정상 응답 반환은 막히지 않는다."""

    class _DeleteBrokenRedis(_FakeRedis):
        def delete(self, *keys):
            raise redis.RedisError("delete 실패")

    fake = _DeleteBrokenRedis()
    mock_redis_factory.return_value = fake

    structured = _StructuredLLM(MagicMock(), _Schema, cache=True, cache_namespace="ns")
    cache_key = structured._cache_key(f"prompt\n\n{structured._parser.get_format_instructions()}")
    fake.store[cache_key] = "not-json-at-all"

    chat = MagicMock()
    chat.invoke.return_value = _make_response('{"field1": "fresh"}')
    structured._chat = chat

    result = structured.invoke("prompt")

    assert result == _Schema(field1="fresh")
    chat.invoke.assert_called_once()
