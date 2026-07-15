"""FastAPI 공통 의존성.

AI 서버 내부 폐쇄(무인증 공개 차단, A안 3/3) — 스프링이 부착하는 `X-Internal-Key`를
FastAPI가 검증한다. nginx 공개 `/ai/` 제거(1차 방어)에 더한 심층방어 계층.
"""
import hmac
import os

from fastapi import Header, HTTPException, status


def verify_internal_key(x_internal_key: str | None = Header(default=None)) -> None:
    """`/ai/*` 라우트 전용 내부키 검증 의존성.

    - `AI_INTERNAL_KEY` 미설정 → 검증 비활성(내부망·nginx 차단이 1차 방어, 키는 심층방어).
      로컬 개발·기존 무키 테스트가 그대로 통과하도록 하는 의도된 동작.
    - 설정됨 → 헤더가 없거나 값이 다르면 401. `hmac.compare_digest`로 상수시간 비교(타이밍 공격 방지).

    NOTE: `os.getenv`는 반드시 함수 **내부**에서 호출(모듈 임포트 시점 아님) —
    테스트가 `patch.dict(os.environ, ...)`로 주입할 수 있게 한다.
    """
    expected = os.getenv("AI_INTERNAL_KEY")
    if not expected:
        return
    # bytes 비교 — 비-ASCII 헤더값에서 str 혼용 시 compare_digest가 TypeError(500) 내는 것 방지.
    if x_internal_key is None or not hmac.compare_digest(
        x_internal_key.encode(), expected.encode()
    ):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="unauthorized")
