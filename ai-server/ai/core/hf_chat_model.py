"""HF Inference Providers 전용 커스텀 ChatModel (GitHub #438 / HAJA-279).

배경(컨테이너 실측, huggingface_hub 0.36.2 / langchain-huggingface 0.1.2):
- langchain_huggingface의 `HuggingFaceEndpoint(...)`는 HF Inference Providers 전환 이후
  task(미지정/text-generation/conversational 무관)와 상관없이 construction 단계에서
  `ValidationError: Could not authenticate`로 항상 실패한다 — task만 바꿔서는 해결되지 않음이
  확인됐다. 그래서 `ChatHuggingFace`/`HuggingFaceEndpoint` 조합은 더 이상 쓰지 않는다.
- 반면 `huggingface_hub.InferenceClient(token=...).chat_completion(messages=[...], model=...)`은
  정상 동작한다. 신규 의존성 추가 없이(huggingface_hub는 langchain-huggingface의 기존 의존성)
  이를 감싸는 얇은 `BaseChatModel` 서브클래스로 대체한다.
- `Qwen/Qwen3-8B`는 reasoning 모델이라 최종 답이 `message.content`가 아니라 별도 필드
  (raw 응답 키 `reasoning_content` 또는 huggingface_hub가 매핑하는 `reasoning`)에 담기고,
  max_tokens가 부족하면 `content`가 None일 수 있다. 이 클래스는 `content`를 우선 사용하고,
  비어 있으면 reasoning 필드에서 `<think>...</think>` 블록 이후의 텍스트를 최종 답으로 추출한다.

with_structured_output()은 이 클래스가 아니라 `llm_client.CachedLLM`/`_StructuredLLM`(프롬프트에
JSON 스키마 지시 + `PydanticOutputParser` 파싱)에서 처리한다(AI_개발_컨벤션.md §4) — 이 클래스는
`.invoke(prompt: str)` 하나만 지원하면 모든 체인(report/briefing/defect_explain)과 호환된다.
`response_format`/`tools`가 필요한 향후 호출자를 위해 `_generate`가 받은 kwargs 중 HF
`chat_completion`이 지원하는 것만 그대로 전달한다(전달만 하고 이 파일이 직접 사용하지는 않음).
"""
from typing import Any, Optional

from huggingface_hub import InferenceClient
from langchain_core.callbacks import CallbackManagerForLLMRun
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, BaseMessage
from langchain_core.outputs import ChatGeneration, ChatResult
from pydantic import Field, SecretStr

# chat_completion()에 그대로 통과시켜도 안전한 선택적 인자 — 현재 체인들은 아무도 넘기지 않지만
# (구조화 출력은 프롬프트 지시 방식이라 필요 없음) 향후 호출자가 invoke(..., response_format=...)
# 형태로 넘기면 그대로 HF Inference에 전달되도록 화이트리스트로 통과시킨다.
_PASSTHROUGH_KWARGS = ("response_format", "tools", "tool_choice", "top_p", "seed")

_THINK_OPEN_TAG = "<think>"
_THINK_CLOSE_TAG = "</think>"

_ROLE_MAP = {
    "human": "user",
    "ai": "assistant",
    "system": "system",
    "tool": "tool",
    "function": "function",
}


def extract_final_answer(reasoning_text: str) -> str:
    """Qwen3 reasoning 모델의 reasoning 텍스트에서 최종 답만 뽑는다.
    - `</think>`가 있으면 그 이후(=최종 답)를 반환
    - `<think>`만 있고 `</think>`가 없으면(사고 과정이 잘려 최종 답 미완성) 빈 문자열 반환
      — 사고 과정 원문이 최종 답으로 새지 않도록(#448 P3)
    - think 태그가 전혀 없으면 전체를 최종 답으로 반환(비-reasoning 응답)"""
    idx = reasoning_text.find(_THINK_CLOSE_TAG)
    if idx != -1:
        return reasoning_text[idx + len(_THINK_CLOSE_TAG):].strip()
    if _THINK_OPEN_TAG in reasoning_text:
        return ""
    return reasoning_text.strip()


def _to_hf_messages(messages: list[BaseMessage]) -> list[dict]:
    return [{"role": _ROLE_MAP.get(m.type, m.type), "content": m.content} for m in messages]


class HFInferenceChatModel(BaseChatModel):
    """`InferenceClient.chat_completion()`을 감싸는 얇은 LangChain ChatModel.

    llm_client.get_llm()이 이 클래스를 생성해 CachedLLM으로 감싼다 — 체인에서 직접 생성하지 않는다
    (AI_개발_컨벤션.md §2).
    """

    model_name: str = Field(alias="model")
    # SecretStr: repr/dumpd(LangSmith 트레이싱 직렬화)에서 토큰이 평문 노출되지 않도록 마스킹.
    # (대체 대상이던 HuggingFaceEndpoint의 huggingfacehub_api_token: SecretStr 보호를 유지 — #438)
    hf_api_token: SecretStr
    temperature: float = 0.1
    timeout: float = 30
    # Qwen3-8B는 reasoning(사고 과정) 이후에 최종 답을 내는 모델이라 max_tokens가 작으면
    # 사고 과정만 채우고 content가 None으로 잘릴 수 있다(컨테이너 실측) — 충분히 크게 설정.
    max_tokens: int = 4096

    model_config = {"populate_by_name": True}

    @property
    def _llm_type(self) -> str:
        return "hf-inference-client-chat"

    def _generate(
        self,
        messages: list[BaseMessage],
        stop: Optional[list[str]] = None,
        run_manager: Optional[CallbackManagerForLLMRun] = None,
        **kwargs: Any,
    ) -> ChatResult:
        client = InferenceClient(token=self.hf_api_token.get_secret_value(), timeout=self.timeout)
        extra = {k: v for k, v in kwargs.items() if k in _PASSTHROUGH_KWARGS and v is not None}
        response = client.chat_completion(
            messages=_to_hf_messages(messages),
            model=self.model_name,
            temperature=self.temperature,
            max_tokens=self.max_tokens,
            stop=stop,
            **extra,
        )
        message = response.choices[0].message
        content = message.content
        if not content:
            # 원본 API 응답이 "reasoning_content" 키를 그대로 내려주면 huggingface_hub가
            # 선언된 필드가 아니라 extra 속성으로 그대로 노출한다(base.py의 parse_obj 참고).
            # HF가 이를 "reasoning"으로 매핑해 내려주는 경우도 있어 둘 다 확인한다.
            reasoning_text = getattr(message, "reasoning_content", None) or getattr(message, "reasoning", None)
            content = extract_final_answer(reasoning_text) if reasoning_text else ""

        if not content or not content.strip():
            # 빈 최종답을 정상값으로 반환하면 CachedLLM이 빈 문자열을 24h 캐싱해 캐시 오염·불투명
            # 실패를 낳는다(#448 P2). 조용히 삼키지 말고 표면화 → CachedLLM 재시도 루프가 처리.
            raise RuntimeError(
                "HF chat_completion이 빈 최종 응답을 반환했습니다(content·reasoning 모두 비었거나 사고 과정만 잘림). "
                f"model={self.model_name}, finish_reason={getattr(response.choices[0], 'finish_reason', None)}"
            )

        usage = response.usage
        usage_metadata = None
        if usage is not None:
            usage_metadata = {
                "input_tokens": usage.prompt_tokens or 0,
                "output_tokens": usage.completion_tokens or 0,
                "total_tokens": usage.total_tokens or 0,
            }

        ai_message = AIMessage(content=content, usage_metadata=usage_metadata)
        return ChatResult(generations=[ChatGeneration(message=ai_message)])
