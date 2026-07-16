"""FastAPI /docs·/redoc·/openapi.json 노출 여부가 APP_ENV로 제어되는지 검증 (#242 / HAJA-194).

fail-closed: 명시적으로 APP_ENV=dev(또는 local)일 때만 노출, 그 외(미설정 포함 전부)는 차단.
prod 신호를 빠뜨린 배포 경로에서 노출되는 fail-open을 막기 위해 화이트리스트 방식으로 판별한다(PR #245 P2 픽스).

- APP_ENV 미설정(기본): /docs·/openapi.json 404 — fail-closed
- APP_ENV=dev: /docs·/openapi.json 200 — 로컬 개발 편의(docker-compose.override.yml에서 명시 주입)
- APP_ENV=prod: /docs·/redoc·/openapi.json 모두 404 — 무인증 내부 AI 스키마 노출 차단

main.py는 모듈 로드 시점에 os.getenv("APP_ENV")로 docs_url 등을 확정하므로, env를 바꾼 뒤
`importlib.reload(main)`로 앱을 재생성해야 반영된다.
"""
import importlib

from fastapi.testclient import TestClient

import main


def test_default_unset_env_disables_docs_and_openapi(monkeypatch):
    # APP_ENV 미설정 상태를 명시적으로 보장(다른 테스트의 env 오염 방지) 후 재로드.
    # fail-closed: 미설정은 dev로 간주하지 않고 차단한다.
    monkeypatch.delenv("APP_ENV", raising=False)
    reloaded = importlib.reload(main)
    try:
        default_client = TestClient(reloaded.app)
        assert default_client.get("/docs").status_code == 404
        assert default_client.get("/openapi.json").status_code == 404
    finally:
        importlib.reload(main)  # 다음 테스트를 위해 기본 상태로 원복


def test_dev_env_exposes_docs_and_openapi(monkeypatch):
    monkeypatch.setenv("APP_ENV", "dev")
    reloaded = importlib.reload(main)
    try:
        dev_client = TestClient(reloaded.app)
        assert dev_client.get("/docs").status_code == 200
        assert dev_client.get("/openapi.json").status_code == 200
    finally:
        monkeypatch.undo()
        importlib.reload(main)  # 다음 테스트를 위해 기본 상태로 원복


def test_prod_disables_docs_redoc_and_openapi(monkeypatch):
    monkeypatch.setenv("APP_ENV", "prod")
    reloaded = importlib.reload(main)
    try:
        prod_client = TestClient(reloaded.app)
        assert prod_client.get("/docs").status_code == 404
        assert prod_client.get("/redoc").status_code == 404
        assert prod_client.get("/openapi.json").status_code == 404
    finally:
        monkeypatch.undo()
        importlib.reload(main)  # 다음 테스트를 위해 기본 상태로 원복
