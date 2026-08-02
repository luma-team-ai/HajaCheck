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

from ai.core.llm_client import MAX_RETRIES, CachedLLM, _StructuredLLM, get_llm

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


@patch("ai.core.llm_client._redis")
def test_structured_retries_after_llm_call_failure(mock_redis, caplog):
    """1회차 호출이 네트워크/타임아웃 등으로 실패해도 재시도로 복구한다."""
    chat = MagicMock()
    chat.invoke.side_effect = [RuntimeError("HF 일시 오류"), _response('{"field1": "ok"}')]

    with caplog.at_level(logging.WARNING, logger="ai.core.llm_client"):
        result = _StructuredLLM(chat, _UT28Schema, cache=False).invoke("prompt")

    assert result == _UT28Schema(field1="ok")
    assert chat.invoke.call_count == 2
    assert len(caplog.records) == 1  # 삼킨 1회차 실패가 로그로 남는다


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
    chat = MagicMock()
    chat.invoke.return_value = _response('{"unexpected_field": "스키마 불일치"}')

    with caplog.at_level(logging.WARNING, logger="ai.core.llm_client"):
        # PydanticOutputParser가 던지는 예외 타입은 langchain 버전에 따라 다를 수 있어 Exception으로 받는다
        with pytest.raises(Exception):  # noqa: B017
            _StructuredLLM(chat, _UT28Schema, cache=False).invoke("prompt")

    assert chat.invoke.call_count == MAX_RETRIES + 1  # 무한 재시도 아님
    assert len(caplog.records) == MAX_RETRIES + 1
    assert "재시도 3/3" in caplog.records[-1].getMessage()


if __name__ == "__main__":
    print("Running LLM provider tests...")
    test_get_llm_hf_provider()
    test_get_llm_hf_reads_tuning_env_at_call_time()
    test_get_llm_default_hf_when_unset()
    test_cached_llm_with_structured_output()
    print("OK: all LLM provider tests passed")
