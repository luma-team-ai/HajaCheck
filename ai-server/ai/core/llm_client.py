"""공통 LLM 클라이언트 — ★유일한 LLM 호출 지점 (AI_개발_컨벤션.md §2)

모델명·HF 엔드포인트·타임아웃·재시도·토큰 사용량 로깅을 한 곳에서 관리.
체인에서는 get_llm()만 호출한다. HuggingFaceEndpoint 직접 생성 금지.
모델 교체(HF Serverless <-> Ollama)는 이 파일 + 환경변수만 수정.
"""
import os

DEFAULT_MODEL = os.getenv("LLM_MODEL", "Qwen/Qwen3-8B")


def get_llm(temperature: float = 0.1, cache: bool = True):
    """모든 체인의 시작점.

    - 토큰 사용량은 Redis `ai:usage:{yyyyMMdd}` 에 자동 집계 (관리자 모니터링 연동)
    - 응답 캐시: 프롬프트 해시 키 `ai:cache:{hash}` Redis 캐시 자동 적용
      (개발 중 크레딧 소진 방지, 우회는 cache=False)
    """
    # TODO(AI-LLM 코치): HuggingFaceEndpoint 생성 + 재시도(2회) + 캐시/사용량 래퍼 구현
    raise NotImplementedError("온보딩 세션(7/15) 전 AI-LLM 코치가 구현")
