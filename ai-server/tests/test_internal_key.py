"""내부키 검증 의존성(deps.verify_internal_key) 동작 검증 — A안 3/3 무인증 폐쇄.

- `AI_INTERNAL_KEY` 설정 시: 무헤더/오헤더 401, 정헤더 200
- 미설정 시: 무헤더 200 (검증 비활성 — 기존 무키 동작 유지)
- `/health`: 키 설정돼 있어도 무인증 200 (라우터 밖 → 컨테이너 헬스체크용)

LLM 호출 없는 `/ai/ping`(GET)으로 검증 — 모킹 불필요.
"""
import os
from unittest.mock import patch

from fastapi.testclient import TestClient

from main import app

client = TestClient(app)


@patch.dict(os.environ, {"AI_INTERNAL_KEY": "secret-k"})
def test_ai_route_missing_key_returns_401():
    res = client.get("/ai/ping")
    assert res.status_code == 401
    assert res.json()["detail"] == "unauthorized"


@patch.dict(os.environ, {"AI_INTERNAL_KEY": "secret-k"})
def test_ai_route_wrong_key_returns_401():
    res = client.get("/ai/ping", headers={"X-Internal-Key": "wrong"})
    assert res.status_code == 401


@patch.dict(os.environ, {"AI_INTERNAL_KEY": "secret-k"})
def test_ai_route_correct_key_returns_200():
    res = client.get("/ai/ping", headers={"X-Internal-Key": "secret-k"})
    assert res.status_code == 200
    assert res.json()["success"] is True


@patch.dict(os.environ, {"AI_INTERNAL_KEY": "secret-k"})
def test_post_route_missing_key_returns_401():
    # POST 라우트도 라우터 레벨 의존성으로 동일하게 차단(바디 검증 이전에 401)
    res = client.post("/ai/defect-explain", json={})
    assert res.status_code == 401


def test_ai_route_no_key_configured_allows_no_header():
    # AI_INTERNAL_KEY 미설정 → 검증 비활성. 기존 무키 테스트가 깨지지 않음을 보장.
    os.environ.pop("AI_INTERNAL_KEY", None)
    res = client.get("/ai/ping")
    assert res.status_code == 200
    assert res.json()["success"] is True


@patch.dict(os.environ, {"AI_INTERNAL_KEY": "secret-k"})
def test_health_is_unauthenticated_even_with_key():
    # /health는 ai_router 밖(prefix 없음) → 키가 설정돼 있어도 무헤더 200
    res = client.get("/health")
    assert res.status_code == 200
    assert res.json() == {"status": "ok"}


if __name__ == "__main__":
    test_ai_route_no_key_configured_allows_no_header()
    print("OK: internal key self-check passed (run full suite with pytest)")
