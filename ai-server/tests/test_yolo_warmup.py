"""YOLO 모델 기동 워밍업 검증(코드 리뷰 P2, 사용자 확인 완료 — main.py FastAPI lifespan).

- `_warmup_yolo_model()`: `_load_yolo_model_sync`를 별도 스레드에서 호출하고, 실패해도 예외를
  삼켜 앱을 죽이지 않는다(첫 실제 요청에서 지연 로드로 재시도됨)
- lifespan은 pytest 실행 중(PYTEST_CURRENT_TEST)에는 워밍업 태스크 자체를 만들지 않는다 — 실제
  HF Hub 네트워크 호출 없이 모든 TestClient 기반 테스트가 빠르게 돈다
"""
import asyncio
from unittest.mock import patch

from fastapi.testclient import TestClient

import main


def test_warmup_yolo_model_calls_loader_in_thread():
    with patch("main._load_yolo_model_sync") as mock_load:
        asyncio.run(main._warmup_yolo_model())

    mock_load.assert_called_once()


def test_warmup_yolo_model_swallows_loader_exception():
    # 워밍업 실패(네트워크 장애·체크포인트 없음 등)로 앱 기동 자체가 죽으면 안 된다 —
    # 첫 실제 분석 요청이 get_yolo_model()의 지연 로드 경로로 재시도한다.
    with patch("main._load_yolo_model_sync", side_effect=RuntimeError("HF Hub 연결 실패")):
        asyncio.run(main._warmup_yolo_model())  # 예외가 여기서 전파되면 테스트 실패


def test_lifespan_skips_yolo_warmup_task_while_running_under_pytest():
    # PYTEST_CURRENT_TEST는 pytest가 테스트 실행 동안 항상 설정하므로, 이 테스트 자체가 그 가드를
    # 실제로 태운다 — 목이 호출 안 되면 lifespan이 워밍업 태스크를 아예 안 만들었다는 뜻이다.
    with patch("main._warmup_yolo_model") as mock_warmup:
        with TestClient(main.app) as client:
            res = client.get("/health")
            assert res.status_code == 200

    mock_warmup.assert_not_called()
