"""HFInferenceChatModel 단위 검증 (GitHub #438 / HAJA-279).

- huggingface_hub.InferenceClient.chat_completion() 을 올바른 인자로 호출하는지
- 정상 응답(content 있음)의 경우 그대로 반환
- Qwen3 reasoning 모델 대응: content가 비어 있으면 reasoning_content/reasoning 필드에서
  최종 답을 추출(<think>...</think> 이후 텍스트, 태그 없으면 전체)
- usage_metadata가 CachedLLM._log_usage가 기대하는 형태(dict + total_tokens)로 채워지는지
"""
from types import SimpleNamespace
from unittest.mock import patch

from ai.core.hf_chat_model import HFInferenceChatModel, extract_final_answer


def _fake_response(content=None, reasoning_content=None, reasoning=None, usage=True):
    message = SimpleNamespace(content=content)
    if reasoning_content is not None:
        message.reasoning_content = reasoning_content
    if reasoning is not None:
        message.reasoning = reasoning
    usage_obj = SimpleNamespace(prompt_tokens=10, completion_tokens=5, total_tokens=15) if usage else None
    return SimpleNamespace(choices=[SimpleNamespace(message=message)], usage=usage_obj)


def _make_model(**overrides):
    kwargs = {"model": "Qwen/Qwen3-8B", "hf_api_token": "test-token", "max_tokens": 100}
    kwargs.update(overrides)
    return HFInferenceChatModel(**kwargs)


def test_extract_final_answer_strips_think_block():
    assert extract_final_answer("<think>musing...</think>real final answer") == "real final answer"


def test_extract_final_answer_no_think_tag_returns_stripped_full_text():
    assert extract_final_answer("  just an answer  ") == "just an answer"


def test_invoke_uses_content_when_present():
    model = _make_model()
    with patch("ai.core.hf_chat_model.InferenceClient") as mock_client_cls:
        mock_client = mock_client_cls.return_value
        mock_client.chat_completion.return_value = _fake_response(content="final answer")

        result = model.invoke("hello")

        assert result.content == "final answer"
        assert result.usage_metadata == {"input_tokens": 10, "output_tokens": 5, "total_tokens": 15}


def test_invoke_calls_chat_completion_with_expected_args():
    model = _make_model(temperature=0.3)
    with patch("ai.core.hf_chat_model.InferenceClient") as mock_client_cls:
        mock_client = mock_client_cls.return_value
        mock_client.chat_completion.return_value = _fake_response(content="ok")

        model.invoke("hello world")

        call_kwargs = mock_client.chat_completion.call_args.kwargs
        assert call_kwargs["messages"] == [{"role": "user", "content": "hello world"}]
        assert call_kwargs["model"] == "Qwen/Qwen3-8B"
        assert call_kwargs["temperature"] == 0.3
        assert call_kwargs["max_tokens"] == 100
        mock_client_cls.assert_called_once_with(token="test-token", timeout=model.timeout)


def test_invoke_falls_back_to_reasoning_content_when_content_empty():
    """실측된 원본 API 응답 형태 — reasoning_content 필드에 <think> 블록 포함."""
    model = _make_model()
    with patch("ai.core.hf_chat_model.InferenceClient") as mock_client_cls:
        mock_client = mock_client_cls.return_value
        mock_client.chat_completion.return_value = _fake_response(
            content=None, reasoning_content="<think>고민 중...</think>진짜 최종 답"
        )

        result = model.invoke("hello")

        assert result.content == "진짜 최종 답"


def test_invoke_falls_back_to_reasoning_field_when_content_empty():
    """huggingface_hub가 선언된 필드(reasoning)로 매핑해 내려주는 경우도 대응."""
    model = _make_model()
    with patch("ai.core.hf_chat_model.InferenceClient") as mock_client_cls:
        mock_client = mock_client_cls.return_value
        mock_client.chat_completion.return_value = _fake_response(content=None, reasoning="바로 답변(태그 없음)")

        result = model.invoke("hello")

        assert result.content == "바로 답변(태그 없음)"


def test_invoke_returns_empty_string_when_no_content_and_no_reasoning():
    model = _make_model()
    with patch("ai.core.hf_chat_model.InferenceClient") as mock_client_cls:
        mock_client = mock_client_cls.return_value
        mock_client.chat_completion.return_value = _fake_response(content=None)

        result = model.invoke("hello")

        assert result.content == ""


def test_invoke_handles_missing_usage():
    model = _make_model()
    with patch("ai.core.hf_chat_model.InferenceClient") as mock_client_cls:
        mock_client = mock_client_cls.return_value
        mock_client.chat_completion.return_value = _fake_response(content="ok", usage=False)

        result = model.invoke("hello")

        assert result.usage_metadata is None
