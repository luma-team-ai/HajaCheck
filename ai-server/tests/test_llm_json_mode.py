"""`_StructuredLLM` JSON 모드·관대한 JSON 추출 검증 (#1745).

- 구조화 호출은 response_format={"type":"json_object"}를 전달(LLM_JSON_MODE=off면 미전달)
- 업체가 400으로 거부하면 같은 attempt에서 옵션 없이 재호출하고 이후 JSON 모드를 끈다
- 400 외 오류는 기존 재시도 흐름
- 코드펜스·앞뒤 설명문이 섞인 응답도 파싱, 순수 JSON은 그대로, 캐시 히트 경로도 동일
"""
from unittest.mock import MagicMock, patch

import pytest
from pydantic import BaseModel

import ai.core.llm_client as lc
from ai.core.llm_client import _extract_json, _StructuredLLM


class _FakeRedis:
    def __init__(self):
        self.store = {}

    def get(self, key):
        return self.store.get(key)

    def setex(self, key, ttl, value):
        self.store[key] = value

    def delete(self, *keys):
        for k in keys:
            self.store.pop(k, None)


class _Schema(BaseModel):
    field1: str


def _resp(content: str):
    r = MagicMock()
    r.content = content
    r.usage_metadata = {"total_tokens": 1}
    return r


def _bad_request():
    from huggingface_hub.errors import BadRequestError

    return BadRequestError("400 bad request", response=MagicMock(status_code=400))


@pytest.fixture(autouse=True)
def _reset_json_mode(monkeypatch):
    monkeypatch.setattr(lc, "_json_mode_rejected", False)
    monkeypatch.delenv("LLM_JSON_MODE", raising=False)
    monkeypatch.setattr(lc, "_log_usage", lambda tokens: None)


def _llm(chat):
    return _StructuredLLM(chat, _Schema, cache=False)


def test_json_mode_kwargs_passed():
    chat = MagicMock()
    chat.invoke.return_value = _resp('{"field1": "a"}')
    assert _llm(chat).invoke("q").field1 == "a"
    assert chat.invoke.call_args.kwargs == {"response_format": {"type": "json_object"}}


@pytest.mark.parametrize("val", ["0", "false", "off", "OFF"])
def test_json_mode_off_by_env(monkeypatch, val):
    monkeypatch.setenv("LLM_JSON_MODE", val)
    chat = MagicMock()
    chat.invoke.return_value = _resp('{"field1": "a"}')
    _llm(chat).invoke("q")
    assert chat.invoke.call_args.kwargs == {}


def test_bad_request_retries_without_option_and_disables_afterwards():
    chat = MagicMock()
    chat.invoke.side_effect = [_bad_request(), _resp('{"field1": "ok"}'), _resp('{"field1": "next"}')]
    llm = _llm(chat)
    assert llm.invoke("q").field1 == "ok"
    assert chat.invoke.call_count == 2  # 같은 attempt 안에서 즉시 재호출
    assert chat.invoke.call_args_list[0].kwargs == {"response_format": {"type": "json_object"}}
    assert chat.invoke.call_args_list[1].kwargs == {}
    assert lc._json_mode_rejected is True

    assert llm.invoke("q2").field1 == "next"
    assert chat.invoke.call_args_list[2].kwargs == {}


def test_non_400_error_uses_existing_retry_flow():
    chat = MagicMock()
    chat.invoke.side_effect = [RuntimeError("503"), _resp('{"field1": "ok"}')]
    assert _llm(chat).invoke("q").field1 == "ok"
    assert lc._json_mode_rejected is False  # 503은 JSON 모드를 끄지 않는다
    assert chat.invoke.call_args_list[1].kwargs == {"response_format": {"type": "json_object"}}


@pytest.mark.parametrize(
    "content",
    [
        '```json\n{"field1": "x"}\n```',
        '```\n{"field1": "x"}\n```',
        '다음은 결과입니다.\n{"field1": "x"}\n이상입니다.',
        '설명 ```json\n{"field1": "x"}\n``` 끝',
        '{"field1": "x"}',
    ],
)
def test_tolerant_json_extraction(content):
    chat = MagicMock()
    chat.invoke.return_value = _resp(content)
    assert _llm(chat).invoke("q").field1 == "x"


def test_extract_json_pure_json_unchanged_and_braces_in_strings():
    pure = ' {"field1": "a}b{"} '
    assert _extract_json(pure) == pure
    assert _extract_json('앞 {"field1": "a}b{"} 뒤') == '{"field1": "a}b{"}'
    assert _extract_json('{"a": {"b": 1}} trailing {') == '{"a": {"b": 1}}'


def test_extract_json_failure_returns_original():
    assert _extract_json("그냥 평문입니다") == "그냥 평문입니다"
    assert _extract_json("{깨진 json") == "{깨진 json"


def test_plain_text_still_fails_after_retries():
    chat = MagicMock()
    chat.invoke.return_value = _resp("평문 답변")
    with pytest.raises(Exception):
        _llm(chat).invoke("q")
    assert chat.invoke.call_count == lc.MAX_RETRIES + 1


@patch("ai.core.llm_client._redis")
def test_cache_hit_path_applies_extraction(mock_redis_factory):
    fake = _FakeRedis()
    mock_redis_factory.return_value = fake
    chat = MagicMock()
    llm = _StructuredLLM(chat, _Schema, cache=True)
    full_prompt = f"q\n\n{llm._parser.get_format_instructions()}"
    fake.setex(llm._cache_key(full_prompt), 100, '```json\n{"field1": "cached"}\n```')
    assert llm.invoke("q").field1 == "cached"
    chat.invoke.assert_not_called()
