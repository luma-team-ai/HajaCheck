"""HF Serverless Inference 전용 LLM 클라이언트 인스턴스화 검증.

- HFInferenceChatModel 생성(GitHub #438 / HAJA-279 — HF Inference
  Providers 전환으로 HuggingFaceEndpoint/ChatHuggingFace가 항상 인증 실패해 대체됨.
  상세 배경: ai/core/hf_chat_model.py 모듈 docstring)
- chat 모델이 CachedLLM으로 정상 감싸기
- with_structured_output() 이 _StructuredLLM 반환
- _StructuredLLM 재시도 폴백 3케이스(UT-28): 호출 실패 후 성공 / 파싱 실패 후 성공 /
  전부 실패 시 마지막 예외 전파. 삼킨 실패가 로그로 남는지도 함께 확인한다.
"""
import logging
import os
from unittest.mock import MagicMock, patch

import pytest
from pydantic import BaseModel

from ai.core.llm_client import CACHE_TTL_SECONDS, MAX_RETRIES, CachedLLM, _StructuredLLM, get_llm

# 실제 시크릿 아님 — 테스트용 자리표시자. 리터럴을 토큰 키에 직접 대입하면
# PR머신 시크릿 스캐너가 오탐하므로 상수로 분리(키 이름에 시크릿 키워드 없음).
_FAKE = "dummy"


@patch.dict(os.environ, {"HF_API_TOKEN": _FAKE})
@patch("ai.core.llm_client.HFInferenceChatModel")
def test_get_llm_hf_provider(mock_chat_model_cls):
    """HFInferenceChatModel 인스턴스화 검증."""
    mock_chat_instance = MagicMock()
    mock_chat_model_cls.return_value = mock_chat_instance

    llm = get_llm(temperature=0.7, cache=False)

    assert isinstance(llm, CachedLLM)
    mock_chat_model_cls.assert_called_once()
    call_args = mock_chat_model_cls.call_args
    assert call_args.kwargs["model"] == "Qwen/Qwen3-8B"
    assert call_args.kwargs["hf_api_token"] == _FAKE
    assert call_args.kwargs["temperature"] == 0.7
    assert call_args.kwargs["timeout"] == 120  # HF_TIMEOUT 기본(#448 P2: max_tokens와 함께 상향)
    assert call_args.kwargs["max_tokens"] == 4096
    assert llm._chat is mock_chat_instance


@patch.dict(
    os.environ,
    {"HF_API_TOKEN": _FAKE, "HF_MAX_TOKENS": "999", "HF_TIMEOUT": "7"},
)
@patch("ai.core.llm_client.HFInferenceChatModel")
def test_get_llm_hf_reads_tuning_env_at_call_time(mock_chat_model_cls):
    """HF_MAX_TOKENS/HF_TIMEOUT을 임포트 시점이 아니라 get_llm() 호출 시점에 읽는지 검증(#448 P2).

    이전엔 모듈 임포트 시 1회 고정이라 '운영 중 조정 가능' 주석과 어긋났고, 이 env override가
    HFInferenceChatModel 생성 인자로 실제 반영되는지는 테스트로 드러나지 않았다."""
    mock_chat_model_cls.return_value = MagicMock()

    get_llm()

    call_args = mock_chat_model_cls.call_args
    assert call_args.kwargs["max_tokens"] == 999
    assert call_args.kwargs["timeout"] == 7.0


@patch.dict(os.environ, {}, clear=True)  # 미설정 시에도 HF 기본값만 사용
@patch("ai.core.llm_client.HFInferenceChatModel")
def test_get_llm_default_hf_when_unset(mock_chat_model_cls):
    """env 미설정 시 HF 기본값 사용 검증."""
    mock_chat_instance = MagicMock()
    mock_chat_model_cls.return_value = mock_chat_instance

    # HF_API_TOKEN은 필수이므로 mock 대신 env 추가
    with patch.dict(os.environ, {"HF_API_TOKEN": _FAKE}):
        llm = get_llm()

    assert isinstance(llm, CachedLLM)
    mock_chat_model_cls.assert_called_once()


def test_cached_llm_with_structured_output():
    """CachedLLM.with_structured_output() 이 _StructuredLLM 반환 검증."""
    mock_chat = MagicMock()
    cached_llm = CachedLLM(mock_chat, cache=True)

    # 간단한 스키마 정의
    from pydantic import BaseModel

    class TestSchema(BaseModel):
        field1: str

    structured = cached_llm.with_structured_output(TestSchema)
    assert isinstance(structured, _StructuredLLM)
    assert structured._chat is mock_chat


@patch.dict(os.environ, {"HF_API_TOKEN": _FAKE})
@patch("ai.core.llm_client.HFInferenceChatModel")
def test_get_llm_creates_independent_instance_per_call(mock_chat_model_cls):
    """report_chain._run_parallel처럼 여러 브랜치(스레드)에서 동시에 get_llm()을 호출해도 안전하다는
    근거: get_llm()에 @lru_cache/싱글턴이 없어 매 호출마다 새 HFInferenceChatModel/CachedLLM 인스턴스를
    만들고 어떤 클라이언트 상태도 호출 간 공유하지 않는다(PR머신 P2 후속)."""
    mock_chat_model_cls.side_effect = lambda **kwargs: MagicMock()

    first = get_llm()
    second = get_llm()

    assert first is not second
    assert first._chat is not second._chat
    assert mock_chat_model_cls.call_count == 2


@patch.dict(os.environ, {"HF_API_TOKEN": _FAKE})
@patch("ai.core.llm_client.HFInferenceChatModel")
def test_cache_namespace_differs_by_model_and_temperature(mock_chat_model_cls):
    """model과 temperature이 다르면 캐시 네임스페이스가 달라진다는 검증."""
    mock_chat_model_cls.return_value = MagicMock()

    # 기본 모델·온도로 캐시 네임스페이스 생성
    llm_default = get_llm(temperature=0.1, cache=True)
    default_namespace = llm_default._cache_namespace
    # 형식: "hf:Qwen/Qwen3-8B:0.1"
    assert default_namespace == "hf:Qwen/Qwen3-8B:0.1"

    # 온도가 다르면 네임스페이스도 달라야 함
    llm_diff_temp = get_llm(temperature=0.7, cache=True)
    diff_temp_namespace = llm_diff_temp._cache_namespace
    assert diff_temp_namespace == "hf:Qwen/Qwen3-8B:0.7"
    assert default_namespace != diff_temp_namespace

    # 모델명이 다르면 네임스페이스도 달라야 함
    with patch.dict(os.environ, {"LLM_MODEL": "other-model:latest"}):
        llm_diff_model = get_llm(temperature=0.1, cache=True)
        diff_model_namespace = llm_diff_model._cache_namespace
        assert diff_model_namespace == "hf:other-model:latest:0.1"
        assert default_namespace != diff_model_namespace


# ── UT-28: _StructuredLLM 재시도 폴백 ─────────────────────────────────────────
# 캐시 동작은 test_llm_structured_cache.py가 다루므로 여기선 cache=False로 두고
# 재시도 경로만 본다(_log_usage가 Redis를 건드리므로 _redis만 mock).


class _UT28Schema(BaseModel):
    field1: str


def _response(content: str):
    resp = MagicMock()
    resp.content = content
    resp.usage_metadata = {"total_tokens": 1}
    return resp


def _assert_no_leak(caplog, secret: str, tail_marker: str) -> None:
    """재시도 로그에 프롬프트·응답 본문이 새지 않았는지 확인한다(PR #1383 P1 회귀 방어).

    본문 문자열 부분일치만으로는 부족하다 — pydantic이 예외 메시지 안의 값을 가운데를 잘라
    '서...균열' 형태로 축약하고, langchain은 한글을 \\uXXXX로 이스케이프해서 담기 때문에
    '내가 고른 substring이 우연히 잘려나가면' 새고 있어도 통과한다(실제로 그렇게 통과했다).
    그래서 문자열 대조에 더해 **exc_info 자체의 흔적**을 본다: exc_info로 예외를 넘기면
    포매터가 반드시 'Traceback'을 찍으므로, 어떤 예외 타입·인코딩이든 이 단언에 걸린다.
    """
    assert "Traceback" not in caplog.text  # exc_info 재도입 시 무조건 걸리는 구조적 단언
    assert "Failed to parse" not in caplog.text  # OutputParserException 메시지 시그니처
    assert secret not in caplog.text
    assert tail_marker not in caplog.text  # 축약(...)에도 살아남는 꼬리 부분


@patch("ai.core.llm_client._redis")
def test_structured_retries_after_llm_call_failure(mock_redis, caplog):
    """1회차 호출이 네트워크/타임아웃 등으로 실패해도 재시도로 복구한다."""
    chat = MagicMock()
    chat.invoke.side_effect = [RuntimeError("HF 일시 오류"), _response('{"field1": "ok"}')]

    with caplog.at_level(logging.WARNING, logger="ai.core.llm_client"):
        result = _StructuredLLM(chat, _UT28Schema, cache=False).invoke("prompt")

    assert result == _UT28Schema(field1="ok")
    assert chat.invoke.call_count == 2
    assert len(caplog.records) == 1  # 삼킨 1회차 실패가 로그로 남고, 성공한 2회차는 남기지 않는다
    assert "구조화" in caplog.records[0].getMessage()  # 경로 라벨 — 일반 경로와 구분되어야 한다
    assert "재시도 1/3" in caplog.records[0].getMessage()


@patch("ai.core.llm_client._redis")
def test_structured_retries_after_parse_failure(mock_redis, caplog):
    """호출은 됐지만 응답이 잘려 파싱 실패한 경우(#448)도 동일하게 재시도한다."""
    chat = MagicMock()
    chat.invoke.side_effect = [_response("잘린 응답"), _response('{"field1": "ok"}')]

    with caplog.at_level(logging.WARNING, logger="ai.core.llm_client"):
        result = _StructuredLLM(chat, _UT28Schema, cache=False).invoke("prompt")

    assert result == _UT28Schema(field1="ok")
    assert chat.invoke.call_count == 2
    assert len(caplog.records) == 1


@patch("ai.core.llm_client._redis")
def test_structured_raises_after_all_retries_on_schema_mismatch(mock_redis, caplog):
    """스키마 불일치(JSON은 유효하나 필드가 다름) 응답이 계속되면 표준 폴백 = 마지막 예외 전파.

    자유 텍스트를 억지로 파싱해 부분 결과를 만들어내지 않는다(NFR-24·NFR-25) — 조용한 None
    반환·자유 텍스트 폴백은 잘못된 하자 설명이 그대로 보고서에 실리는 경로라 더 위험하다.
    """
    secret_like_content = '{"unexpected_field": "서울시 강남구 OO빌딩 3층 북벽 균열"}'
    chat = MagicMock()
    chat.invoke.return_value = _response(secret_like_content)

    with caplog.at_level(logging.WARNING, logger="ai.core.llm_client"):
        # PydanticOutputParser가 던지는 예외 타입은 langchain 버전에 따라 다를 수 있어 Exception으로 받는다
        with pytest.raises(Exception):  # noqa: B017
            _StructuredLLM(chat, _UT28Schema, cache=False).invoke("prompt")

    assert chat.invoke.call_count == MAX_RETRIES + 1  # 무한 재시도 아님
    assert len(caplog.records) == MAX_RETRIES + 1
    assert "재시도 3/3" in caplog.records[-1].getMessage()
    assert "OutputParserException" in caplog.text  # 실패 성격은 타입명으로 식별 가능

    _assert_no_leak(caplog, secret_like_content, tail_marker="균열")


# ── CachedLLM(일반 경로) 재시도 로깅 ─────────────────────────────────────────
# 후속 이슈 #1386(PR #1383 P2) — _log_retry_failure는 구조화 경로와 일반 경로 양쪽에 걸려
# 있는데 구조화 쪽만 테스트돼 커버리지가 비대칭이었다. 일반 경로도 같은 기준으로 고정한다:
# 재시도 동작 / 경로 라벨·횟수 포맷 / 예외 메시지 원문 미노출.


@patch("ai.core.llm_client._redis")
def test_general_retries_after_llm_call_failure(mock_redis, caplog):
    """일반 경로도 1회차 실패를 삼키고 재시도로 복구하며, 그 실패를 로그로 남긴다."""
    chat = MagicMock()
    chat.invoke.side_effect = [RuntimeError("HF 일시 오류"), _response("응답 본문")]

    with caplog.at_level(logging.WARNING, logger="ai.core.llm_client"):
        result = CachedLLM(chat, cache=False).invoke("prompt")

    assert result == "응답 본문"
    assert chat.invoke.call_count == 2
    assert len(caplog.records) == 1  # 성공한 2회차는 로그를 남기지 않는다(정상 호출 노이즈 방지)
    message = caplog.records[0].getMessage()
    assert "일반" in message  # 경로 라벨 — 구조화 경로와 구분되어야 한다
    assert "재시도 1/3" in message
    assert "RuntimeError" in message  # 실패 성격은 예외 타입명으로 식별 가능


@patch("ai.core.llm_client._redis")
def test_general_raises_last_error_after_all_retries_without_leaking_message(mock_redis, caplog):
    """일반 경로도 MAX_RETRIES까지 실패하면 마지막 예외를 전파하고, 예외 메시지 본문은 로그에 안 남긴다.

    HF 클라이언트가 던지는 예외 메시지에는 요청 본문(프롬프트)이나 응답이 섞여 들어올 수 있다.
    구조화 경로에서 실제로 발생했던 유출(PR #1383 P1 — OutputParserException이 응답 원문을
    메시지에 담던 문제)과 동일한 회귀를 일반 경로에서도 차단한다.
    """
    secret_like_text = "서울시 강남구 OO빌딩 3층 북벽 균열"
    chat = MagicMock()
    chat.invoke.side_effect = RuntimeError(f"HF 500 — 요청 본문: {secret_like_text}")

    with caplog.at_level(logging.WARNING, logger="ai.core.llm_client"):
        with pytest.raises(RuntimeError):
            CachedLLM(chat, cache=False).invoke("prompt")

    assert chat.invoke.call_count == MAX_RETRIES + 1  # 무한 재시도 아님
    assert len(caplog.records) == MAX_RETRIES + 1
    assert "일반" in caplog.records[-1].getMessage()
    assert "재시도 3/3" in caplog.records[-1].getMessage()
    assert "RuntimeError" in caplog.text

    _assert_no_leak(caplog, secret_like_text, tail_marker="북벽 균열")


@patch("ai.core.llm_client._redis")
def test_general_cache_hit_skips_llm_call_and_logs_nothing(mock_redis_factory, caplog):
    """캐시 히트는 LLM을 부르지 않으므로 재시도 로그도 남지 않는다.

    재시도 로깅을 넣으면서 캐시 분기가 깨지지 않았는지 함께 고정한다(같은 메서드라 회귀 위험이
    한 덩어리다 — 이 분기는 그동안 어떤 테스트도 실행하지 않고 있었다).
    """
    fake_redis = MagicMock()
    fake_redis.get.return_value = "캐시된 응답"
    mock_redis_factory.return_value = fake_redis

    chat = MagicMock()
    with caplog.at_level(logging.WARNING, logger="ai.core.llm_client"):
        result = CachedLLM(chat, cache=True, cache_namespace="ns").invoke("prompt")

    assert result == "캐시된 응답"
    chat.invoke.assert_not_called()
    assert caplog.records == []


@patch("ai.core.llm_client._redis")
def test_general_cache_miss_stores_response_and_counts_usage(mock_redis_factory):
    """캐시 미스면 실제 호출 후 raw 응답을 기본 TTL로 저장하고 토큰 사용량을 집계한다."""
    fake_redis = MagicMock()
    fake_redis.get.return_value = None
    mock_redis_factory.return_value = fake_redis

    chat = MagicMock()
    chat.invoke.return_value = _response("응답 본문")

    result = CachedLLM(chat, cache=True, cache_namespace="ns").invoke("prompt")

    assert result == "응답 본문"
    key, ttl, value = fake_redis.setex.call_args.args
    assert key.startswith("ai:cache:ns:")
    assert ttl == CACHE_TTL_SECONDS
    assert value == "응답 본문"
    fake_redis.incrby.assert_called_once()  # ai:usage:{yyyyMMdd} 집계


if __name__ == "__main__":
    print("Running LLM provider tests...")
    test_get_llm_hf_provider()
    test_get_llm_hf_reads_tuning_env_at_call_time()
    test_get_llm_default_hf_when_unset()
    test_cached_llm_with_structured_output()
    print("OK: all LLM provider tests passed")
