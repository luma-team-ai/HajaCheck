"""공통 LLM 클라이언트 — ★유일한 LLM 호출 지점 (AI_개발_컨벤션.md §2)

모델명·엔드포인트·타임아웃·재시도·토큰 사용량 로깅을 한 곳에서 관리.
체인에서는 get_llm()만 호출한다. LLM 클라이언트 직접 생성 금지.
HF Serverless Inference API(Qwen3-8B) 전용.
"""
import hashlib
import os
from datetime import date
from functools import lru_cache

import redis
from langchain_core.output_parsers import PydanticOutputParser

from ai.core.hf_chat_model import HFInferenceChatModel

DEFAULT_MODEL = os.getenv("LLM_MODEL", "Qwen/Qwen3-8B")
MAX_RETRIES = 2
CACHE_TTL_SECONDS = 60 * 60 * 24  # 1일 — 개발 중 반복 질의 크레딧 절약용


@lru_cache
def _redis() -> redis.Redis:
    return redis.from_url(os.environ["REDIS_URL"], decode_responses=True)


def get_redis_client() -> redis.Redis:
    """다른 체인이 동일 Redis 커넥션을 재사용할 공개 통로 (AI_개발_컨벤션.md §0 —
    공통 기반 위에서만 개발). `_redis()`는 모듈 프라이빗이라 이 파일 밖에서 직접 호출할
    수 없으므로, 새 커넥션 로직을 만들지 않고 그대로 노출한다."""
    return _redis()


def _prompt_hash(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


def _log_usage(tokens: int) -> None:
    key = f"ai:usage:{date.today():%Y%m%d}"
    try:
        _redis().incrby(key, tokens)
    except redis.RedisError:
        pass  # 사용량 집계 실패가 응답을 막으면 안 됨


class CachedLLM:
    """get_llm()이 반환하는 래퍼. .invoke()에서만 캐시/재시도/사용량 로깅을 적용한다."""

    def __init__(self, chat_model, cache: bool, cache_namespace: str = "default"):  # chat_model: 모든 provider 호환 LangChain chat 모델
        self._chat = chat_model
        self._cache = cache
        self._cache_namespace = cache_namespace

    def invoke(self, prompt: str):
        cache_key = None
        if self._cache:
            cache_key = f"ai:cache:{self._cache_namespace}:{_prompt_hash(prompt)}"
            cached = _redis().get(cache_key)
            if cached is not None:
                return cached

        last_error: Exception | None = None
        for _ in range(MAX_RETRIES + 1):
            try:
                response = self._chat.invoke(prompt)
                break
            except Exception as e:  # noqa: BLE001 — 재시도 대상은 광범위한 네트워크/타임아웃 오류
                last_error = e
        else:
            raise last_error  # MAX_RETRIES 모두 실패

        content = response.content
        tokens = (response.usage_metadata or {}).get("total_tokens", 0)
        _log_usage(tokens)

        if self._cache:
            _redis().setex(cache_key, CACHE_TTL_SECONDS, content)
        return content

    def with_structured_output(self, schema):
        # langchain 표준 with_structured_output()은 tool_choice 강제 호출(bind_tools) 기반인데
        # HF Serverless Inference가 "auto"/"none" 외 tool_choice를 지원 안 해서 항상 400
        # INVALID_TOOL_CHOICE로 실패함(langchain-ai/langchain#29569, upstream "not planned" —
        # 버전 업그레이드로 해결 안 됨). 프롬프트에 JSON 스키마를 지시하고 직접 파싱하는 방식으로 우회.
        return _StructuredLLM(self._chat, schema)


class _StructuredLLM:
    """with_structured_output() 우회 구현 — 프롬프트 지시 + PydanticOutputParser 파싱."""

    def __init__(self, chat_model, schema):  # chat_model: 모든 provider 호환 LangChain chat 모델
        self._chat = chat_model
        self._parser = PydanticOutputParser(pydantic_object=schema)

    def invoke(self, prompt: str):
        full_prompt = f"{prompt}\n\n{self._parser.get_format_instructions()}"
        last_error: Exception | None = None
        for _ in range(MAX_RETRIES + 1):
            try:
                # invoke를 try 안에 둬 LLM 호출 실패(빈 응답 RuntimeError·타임아웃 등)도 파싱 실패와
                # 동일하게 재시도 대상에 포함(#448 P2: 일시적 truncation을 구조화 출력 경로에서도 흡수).
                response = self._chat.invoke(full_prompt)
                return self._parser.parse(response.content)
            except Exception as e:  # noqa: BLE001 — LLM 호출·파싱 실패 모두 재시도
                last_error = e
        raise last_error


def get_llm(temperature: float = 0.1, cache: bool = True) -> CachedLLM:
    """모든 체인의 시작점.

    - 토큰 사용량은 Redis `ai:usage:{yyyyMMdd}` 에 자동 집계 (관리자 모니터링 연동)
    - 응답 캐시: 키 `ai:cache:hf:{model}:{temperature}:{hash}` Redis 캐시 자동 적용
      (모델/temperature별 네임스페이스 분리)
    - structured output이 필요하면 `get_llm().with_structured_output(Schema)` 사용
      (프롬프트 지시 + PydanticOutputParser 파싱 방식 — 응답 캐시는 거치지 않고, 파싱 실패 시 자체 재시도)
    """
    # 호출 시점에 LLM_MODEL을 읽어 운영 중 조정 가능하게 한다 (#448 P2)
    model_name = os.getenv("LLM_MODEL", DEFAULT_MODEL)
    # HF Inference 튜닝 env도 호출 시점에 읽어, 프로세스 재시작 없이 반영되게 한다(운영 중 조정 가능).
    #   HF_MAX_TOKENS: 응답 토큰 상한 — Qwen3-8B는 reasoning 이후 최종답을 내므로 작으면 content가
    #     None으로 잘림(#438, 컨테이너 실측) → 기본값 크게(4096).
    #   HF_TIMEOUT: 응답 대기 상한(초, 기본 120). max_tokens를 키우면 함께 상향하되, Spring
    #     ai.server.read-timeout-ms(150000)보다 작게 유지할 것(#448 P1 — 역전 시 Spring이 먼저 끊음).
    hf_max_tokens = int(os.getenv("HF_MAX_TOKENS", "4096"))
    hf_timeout = float(os.getenv("HF_TIMEOUT", "120"))
    # langchain_huggingface의 HuggingFaceEndpoint/ChatHuggingFace는 HF Inference Providers
    # 전환 이후 construction 단계에서 항상 인증 실패한다(GitHub #438 / HAJA-279, 컨테이너
    # 실측 — task를 바꿔도 해결 안 됨. 상세: ai/core/hf_chat_model.py 모듈 docstring).
    # huggingface_hub.InferenceClient.chat_completion()은 정상 동작하므로 이를 감싸는
    # 커스텀 BaseChatModel(HFInferenceChatModel)로 대체한다.
    chat_model = HFInferenceChatModel(
        model=model_name,
        hf_api_token=os.environ["HF_API_TOKEN"],
        temperature=temperature,
        timeout=hf_timeout,
        max_tokens=hf_max_tokens,
    )

    cache_namespace = f"hf:{model_name}:{temperature}"
    return CachedLLM(chat_model, cache=cache, cache_namespace=cache_namespace)
