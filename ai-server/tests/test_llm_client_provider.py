"""LLM provider 분기 및 인스턴스화 검증.

- LLM_PROVIDER=hf 일 때 HFInferenceChatModel 생성(GitHub #438 / HAJA-279 — HF Inference
  Providers 전환으로 HuggingFaceEndpoint/ChatHuggingFace가 항상 인증 실패해 대체됨.
  상세 배경: ai/core/hf_chat_model.py 모듈 docstring)
- LLM_PROVIDER=ollama 일 때 ChatOllama 생성
- 각 provider의 chat 모델이 CachedLLM으로 정상 감싸기
- with_structured_output() 이 provider 상관없이 _StructuredLLM 반환
"""
import os
from unittest.mock import MagicMock, patch

from ai.core.llm_client import CachedLLM, _StructuredLLM, get_llm

# 실제 시크릿 아님 — 테스트용 자리표시자. 리터럴을 토큰 키에 직접 대입하면
# PR머신 시크릿 스캐너가 오탐하므로 상수로 분리(키 이름에 시크릿 키워드 없음).
_FAKE = "dummy"


@patch.dict(os.environ, {"LLM_PROVIDER": "hf", "HF_API_TOKEN": _FAKE})
@patch("ai.core.llm_client.HFInferenceChatModel")
def test_get_llm_hf_provider(mock_chat_model_cls):
    """LLM_PROVIDER=hf 일 때 HFInferenceChatModel 인스턴스화 검증."""
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
    {"LLM_PROVIDER": "hf", "HF_API_TOKEN": _FAKE, "HF_MAX_TOKENS": "999", "HF_TIMEOUT": "7"},
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


@patch.dict(os.environ, {"LLM_PROVIDER": "ollama", "OLLAMA_MODEL": "exaone3.5:7.8b", "OLLAMA_BASE_URL": "http://localhost:11434"})
@patch("langchain_ollama.ChatOllama")
def test_get_llm_ollama_provider(mock_chat_ollama):
    """LLM_PROVIDER=ollama 일 때 Ollama 클라이언트 인스턴스화 검증."""
    mock_ollama_instance = MagicMock()
    mock_chat_ollama.return_value = mock_ollama_instance

    llm = get_llm(temperature=0.5, cache=True)

    assert isinstance(llm, CachedLLM)
    mock_chat_ollama.assert_called_once()
    call_args = mock_chat_ollama.call_args
    assert call_args.kwargs["model"] == "exaone3.5:7.8b"
    assert call_args.kwargs["base_url"] == "http://localhost:11434"
    assert call_args.kwargs["temperature"] == 0.5
    assert call_args.kwargs["client_kwargs"] == {"timeout": 30}


@patch.dict(os.environ, {"LLM_PROVIDER": "ollama"})
@patch("langchain_ollama.ChatOllama")
def test_get_llm_ollama_defaults(mock_chat_ollama):
    """LLM_PROVIDER=ollama 일 때 기본값 적용 검증."""
    mock_ollama_instance = MagicMock()
    mock_chat_ollama.return_value = mock_ollama_instance

    llm = get_llm()

    mock_chat_ollama.assert_called_once()
    call_args = mock_chat_ollama.call_args
    assert call_args.kwargs["model"] == "qwen3:8b"
    assert call_args.kwargs["base_url"] == "http://localhost:11434"
    assert call_args.kwargs["temperature"] == 0.1


@patch.dict(os.environ, {}, clear=True)  # LLM_PROVIDER 미설정 → 기본값 "hf"
@patch("ai.core.llm_client.HFInferenceChatModel")
def test_get_llm_default_hf_when_unset(mock_chat_model_cls):
    """LLM_PROVIDER 미설정 시 기본값 'hf' 사용 검증."""
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


@patch.dict(os.environ, {"LLM_PROVIDER": "hf", "HF_API_TOKEN": _FAKE})
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


@patch.dict(os.environ, {"LLM_PROVIDER": "invalid_provider"})
def test_get_llm_invalid_provider_raises_error():
    """LLM_PROVIDER=invalid 일 때 ValueError 발생 검증."""
    import pytest

    with pytest.raises(ValueError, match="LLM_PROVIDER must be 'hf' or 'ollama', got 'invalid_provider'"):
        get_llm()


@patch.dict(os.environ, {"LLM_PROVIDER": "hf", "HF_API_TOKEN": _FAKE})
@patch("ai.core.llm_client.HFInferenceChatModel")
def test_cache_namespace_differs_by_provider_and_model(mock_chat_model_cls):
    """provider와 model이 다르면 캐시 네임스페이스가 달라진다는 검증."""
    mock_chat_model_cls.return_value = MagicMock()

    # HF provider의 기본 모델로 캐시 네임스페이스 생성
    llm_hf = get_llm(temperature=0.1, cache=True)
    hf_namespace = llm_hf._cache_namespace
    # 형식: "hf:Qwen/Qwen3-8B:0.1" (DEFAULT_MODEL + temperature 포함)
    assert hf_namespace.startswith("hf:")
    assert ":0.1" in hf_namespace

    # Ollama provider로 전환 시 다른 네임스페이스 생성
    with patch.dict(os.environ, {"LLM_PROVIDER": "ollama", "OLLAMA_MODEL": "exaone3.5:7.8b"}):
        with patch("langchain_ollama.ChatOllama") as mock_ollama:
            mock_ollama.return_value = MagicMock()
            llm_ollama = get_llm(temperature=0.1, cache=True)
            ollama_namespace = llm_ollama._cache_namespace
            assert ollama_namespace == "ollama:exaone3.5:7.8b:0.1"

    # 네임스페이스가 다르므로 캐시 키도 달라야 함
    assert hf_namespace != ollama_namespace

    # temperature가 다르면 네임스페이스도 달라야 함
    with patch.dict(os.environ, {"LLM_PROVIDER": "hf", "HF_API_TOKEN": _FAKE}):
        llm_hf_diff_temp = get_llm(temperature=0.7, cache=True)
        assert llm_hf_diff_temp._cache_namespace != hf_namespace
        assert ":0.7" in llm_hf_diff_temp._cache_namespace


if __name__ == "__main__":
    print("Running LLM provider tests...")
    test_get_llm_hf_provider()
    test_get_llm_ollama_provider()
    test_get_llm_ollama_defaults()
    test_get_llm_default_hf_when_unset()
    test_cached_llm_with_structured_output()
    print("OK: all LLM provider tests passed")
