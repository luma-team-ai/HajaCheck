"""공통 LLM 클라이언트 — ★유일한 LLM 호출 지점 (AI_개발_컨벤션.md §2)

모델명·HF 엔드포인트·타임아웃·재시도·토큰 사용량 로깅을 한 곳에서 관리.
체인에서는 get_llm()만 호출한다. HuggingFaceEndpoint 직접 생성 금지.
모델 교체(HF Serverless <-> Ollama)는 이 파일 + 환경변수만 수정.
"""
import hashlib
import os
from datetime import date
from functools import lru_cache

import redis
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

    def __init__(self, chat_model: ChatHuggingFace, cache: bool):
        self._chat = chat_model
        self._cache = cache

    def invoke(self, prompt: str):
        cache_key = None
        if self._cache:
            cache_key = f"ai:cache:{_prompt_hash(prompt)}"
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
        return self._chat.with_structured_output(schema)


def get_llm(temperature: float = 0.1, cache: bool = True) -> CachedLLM:
    """모든 체인의 시작점.

    - 토큰 사용량은 Redis `ai:usage:{yyyyMMdd}` 에 자동 집계 (관리자 모니터링 연동)
    - 응답 캐시: 프롬프트 해시 키 `ai:cache:{hash}` Redis 캐시 자동 적용
      (개발 중 크레딧 소진 방지, 우회는 cache=False)
    - structured output이 필요하면 `get_llm().with_structured_output(Schema)` 사용
      (이 경로는 캐시/재시도 래퍼를 거치지 않음 — langchain 표준 방식 그대로)
    """
    endpoint = HuggingFaceEndpoint(
        repo_id=DEFAULT_MODEL,
        huggingfacehub_api_token=os.environ["HF_API_TOKEN"],
        temperature=temperature,
        timeout=30,
    )
    return CachedLLM(ChatHuggingFace(llm=endpoint), cache=cache)
