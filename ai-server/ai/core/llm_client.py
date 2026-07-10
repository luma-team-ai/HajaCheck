"""공통 LLM 클라이언트 — ★유일한 LLM 호출 지점 (AI_개발_컨벤션.md §2)

모델명·엔드포인트·타임아웃·재시도·토큰 사용량 로깅을 한 곳에서 관리.
체인에서는 get_llm()만 호출한다. LLM 클라이언트 직접 생성 금지.
provider 선택(HF Serverless <-> Ollama)은 환경변수(LLM_PROVIDER) 또는 이 파일만 수정.
"""
import hashlib
import os
from datetime import date
from functools import lru_cache

import redis
from langchain_core.output_parsers import PydanticOutputParser
from langchain_huggingface import ChatHuggingFace, HuggingFaceEndpoint

DEFAULT_MODEL = os.getenv("LLM_MODEL", "Qwen/Qwen3-8B")
MAX_RETRIES = 2
CACHE_TTL_SECONDS = 60 * 60 * 24  # 1일 — 개발 중 반복 질의 크레딧 절약용


@lru_cache
def _redis() -> redis.Redis:
    return redis.from_url(os.environ["REDIS_URL"], decode_responses=True)


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
            response = self._chat.invoke(full_prompt)
            try:
                return self._parser.parse(response.content)
            except Exception as e:  # noqa: BLE001 — 파싱 실패(형식 어긋난 출력)는 재시도 대상
                last_error = e
        raise last_error


def get_llm(temperature: float = 0.1, cache: bool = True) -> CachedLLM:
    """모든 체인의 시작점.

    - 토큰 사용량은 Redis `ai:usage:{yyyyMMdd}` 에 자동 집계 (관리자 모니터링 연동)
    - 응답 캐시: 프롬프트 해시 키 `ai:cache:{hash}` Redis 캐시 자동 적용
      (개발 중 크레딧 소진 방지, 우회는 cache=False)
    - structured output이 필요하면 `get_llm().with_structured_output(Schema)` 사용
      (프롬프트 지시 + PydanticOutputParser 파싱 방식 — 응답 캐시는 거치지 않고, 파싱 실패 시 자체 재시도)
    """
    llm_provider = os.getenv("LLM_PROVIDER", "hf").lower()
    if llm_provider not in ("hf", "ollama"):
        raise ValueError(f"LLM_PROVIDER must be 'hf' or 'ollama', got '{llm_provider}'")

    if llm_provider == "ollama":
        model_name = os.getenv("OLLAMA_MODEL", "exaone3.5:7.8b")
        from langchain_ollama import ChatOllama  # noqa: F401 — 조건부 import

        chat_model = ChatOllama(
            model=model_name,
            base_url=os.getenv("OLLAMA_BASE_URL", "http://localhost:11434"),
            temperature=temperature,
            timeout=30,
        )
    else:  # default "hf"
        model_name = DEFAULT_MODEL
        endpoint = HuggingFaceEndpoint(
            repo_id=model_name,
            huggingfacehub_api_token=os.environ["HF_API_TOKEN"],
            temperature=temperature,
            timeout=30,
        )
        chat_model = ChatHuggingFace(llm=endpoint)

    cache_namespace = f"{llm_provider}:{model_name}"
    return CachedLLM(chat_model, cache=cache, cache_namespace=cache_namespace)
