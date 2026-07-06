"""스켈레톤 기본 테스트 — CI(pytest) 그린 유지용. 기능 테스트는 각 담당이 추가."""
from main import app, health


def test_health():
    assert health() == {"status": "ok"}


def test_ai_router_registered():
    assert "/ai/ping" in app.openapi()["paths"]
