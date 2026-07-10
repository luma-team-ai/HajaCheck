"""LLM provider 분기 및 인스턴스화 검증.

- LLM_PROVIDER=hf 일 때 HuggingFaceEndpoint + ChatHuggingFace 생성
- LLM_PROVIDER=ollama 일 때 ChatOllama 생성
- 각 provider의 chat 모델이 CachedLLM으로 정상 감싸기
- with_structured_output() 이 provider 상관없이 _StructuredLLM 반환
"""
import os
from unittest.mock import MagicMock, patch

from ai.core.llm_client import CachedLLM, _StructuredLLM, get_llm


@patch.dict(os.environ, {"LLM_PROVIDER": "hf", "HF_API_TOKEN": "test-token"})
@patch("ai.core.llm_client.HuggingFaceEndpoint")
@patch("ai.core.llm_client.ChatHuggingFace")
def test_get_llm_hf_provider(mock_chat_hf, mock_endpoint):
    """LLM_PROVIDER=hf 일 때 HF 클라이언트 인스턴스화 검증."""
    mock_endpoint_instance = MagicMock()
    mock_endpoint.return_value = mock_endpoint_instance
    mock_chat_instance = MagicMock()
    mock_chat_hf.return_value = mock_chat_instance

    llm = get_llm(temperature=0.7, cache=False)

    assert isinstance(llm, CachedLLM)
    mock_endpoint.assert_called_once()
    call_args = mock_endpoint.call_args
    assert call_args.kwargs["huggingfacehub_api_token"] == "test-token"
    assert call_args.kwargs["temperature"] == 0.7
    assert call_args.kwargs["timeout"] == 30
    mock_chat_hf.assert_called_once_with(llm=mock_endpoint_instance)


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


@patch.dict(os.environ, {"LLM_PROVIDER": "ollama"})
@patch("langchain_ollama.ChatOllama")
def test_get_llm_ollama_defaults(mock_chat_ollama):
    """LLM_PROVIDER=ollama 일 때 기본값 적용 검증."""
    mock_ollama_instance = MagicMock()
    mock_chat_ollama.return_value = mock_ollama_instance

    llm = get_llm()

    mock_chat_ollama.assert_called_once()
    call_args = mock_chat_ollama.call_args
    assert call_args.kwargs["model"] == "exaone3.5:7.8b"
    assert call_args.kwargs["base_url"] == "http://localhost:11434"
    assert call_args.kwargs["temperature"] == 0.1


@patch.dict(os.environ, {}, clear=True)  # LLM_PROVIDER 미설정 → 기본값 "hf"
@patch("ai.core.llm_client.HuggingFaceEndpoint")
@patch("ai.core.llm_client.ChatHuggingFace")
def test_get_llm_default_hf_when_unset(mock_chat_hf, mock_endpoint):
    """LLM_PROVIDER 미설정 시 기본값 'hf' 사용 검증."""
    mock_endpoint_instance = MagicMock()
    mock_endpoint.return_value = mock_endpoint_instance
    mock_chat_instance = MagicMock()
    mock_chat_hf.return_value = mock_chat_instance

    # HF_API_TOKEN은 필수이므로 mock 대신 env 추가
    with patch.dict(os.environ, {"HF_API_TOKEN": "test-token"}):
        llm = get_llm()

    assert isinstance(llm, CachedLLM)
    mock_endpoint.assert_called_once()
    mock_chat_hf.assert_called_once()


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


if __name__ == "__main__":
    print("Running LLM provider tests...")
    test_get_llm_hf_provider()
    test_get_llm_ollama_provider()
    test_get_llm_ollama_defaults()
    test_get_llm_default_hf_when_unset()
    test_cached_llm_with_structured_output()
    print("OK: all LLM provider tests passed")
