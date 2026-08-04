"""하자 탐지 모델 기동 워밍업 검증(코드 리뷰 P2, 사용자 확인 완료 — main.py FastAPI lifespan).

- `_warmup_defect_models()`: `_load_defect_models_sync`를 별도 스레드에서 호출하고, 실패해도
  예외를 삼켜 앱을 죽이지 않는다(첫 실제 요청에서 지연 로드로 재시도됨)
- lifespan은 pytest 실행 중(PYTEST_CURRENT_TEST)에는 워밍업 태스크 자체를 만들지 않는다 — 실제
  HF Hub 네트워크 호출 없이 모든 TestClient 기반 테스트가 빠르게 돈다

함수명은 2026-07-27 6차 rebase 때 `_warmup_yolo_model`/`_load_yolo_model_sync`에서 갱신됐다 —
크랙(U-Net)까지 포함한 하자 탐지 모델 3종을 로드하므로 "YOLO"라는 이름이 더 이상 정확하지
않았다(PR #973 P3 리뷰).
"""
import asyncio
import contextlib
from unittest.mock import MagicMock, patch

import pytest
from fastapi.testclient import TestClient

import main


def test_warmup_defect_models_calls_loader_in_thread():
    with patch("main._load_defect_models_sync") as mock_load:
        asyncio.run(main._warmup_defect_models())

    mock_load.assert_called_once()


def test_load_defect_models_sync_includes_card_model():
    """카드 검출 모델(YOLO-World + CLIP)도 워밍업 대상이어야 한다(#1547 머신 검수 P1).

    빠지면 첫 균열 탐지 요청이 가중치 24.7MB + CLIP ViT-B/32 338MB 콜드스타트를 그대로 떠안는다 —
    기존 3종을 미리 로드하는 이유(#701)와 같은 실패 모드다. `_load_defect_models_sync`는 함수
    내부에서 지연 임포트하므로 원본 모듈 속성을 패치해야 한다(실제 다운로드 차단 겸용).
    """
    with patch("ai.core.card_client.warmup_card_model") as mock_card, \
         patch("ai.core.unet_client.get_crack_model") as mock_crack, \
         patch("ai.core.yolo_client.get_yolo_model") as mock_yolo, \
         patch("ai.core.embeddings.get_embeddings") as mock_embeddings:
        main._load_defect_models_sync()

    mock_card.assert_called_once()
    # 기존 워밍업 대상이 카드 추가로 밀려나지 않았는지 함께 고정한다.
    mock_crack.assert_called_once()
    assert mock_yolo.call_count == 2
    mock_embeddings.assert_called_once()


def test_card_warmup_failure_does_not_block_embedding_warmup():
    """#1594 3번 — 신규 카드 경로(런타임에 GitHub YOLO-World + CLIP 338MB를 새로 받는다) 실패가
    기존 RAG 임베딩 워밍업을 건너뛰게 만들면 안 된다.

    예전 구조는 카드 워밍업이 `get_embeddings()`보다 앞이고 예외는 `_warmup_defect_models()`
    바깥에서 한 번에 잡혀서, 카드가 실패하면 bge-m3 워밍업이 아예 실행되지 않았다 —
    부가 기능 실패가 기존 기능(RAG 첫 호출 콜드스타트)을 회귀시키는 실패 모드다.
    """
    with patch(
        "ai.core.card_client.warmup_card_model", side_effect=RuntimeError("YOLO-World 다운로드 실패")
    ) as mock_card, patch("ai.core.unet_client.get_crack_model"), patch(
        "ai.core.yolo_client.get_yolo_model"
    ), patch("ai.core.embeddings.get_embeddings") as mock_embeddings:
        main._load_defect_models_sync()  # 예외가 전파되면 테스트 실패

    mock_card.assert_called_once()
    mock_embeddings.assert_called_once()


@pytest.mark.parametrize(
    "failing_target",
    [
        "ai.core.unet_client.get_crack_model",
        "ai.core.yolo_client.get_yolo_model",
        "ai.core.card_client.warmup_card_model",
    ],
)
def test_any_defect_model_failure_does_not_block_embedding_warmup(failing_target):
    """#1594 코드리뷰 P2 — 카드만 격리해선 부족하다. 그 앞의 U-Net/YOLO 3종도 런타임 HF Hub
    다운로드 경로라(unet_client.py·yolo_client.py) 하나만 던져도 `_warmup_defect_models()`의
    바깥 핸들러까지 튕겨 뒤에 있던 `get_embeddings()`가 통째로 실행되지 않는다 — 이슈 3번이
    지적한 실패 모드가 "카드" → "U-Net/YOLO"로 이름만 바뀐 채 남아 있던 상태다.

    임베딩 워밍업을 맨 앞으로 올렸으므로, **어떤** 하자 모델이 실패해도 이미 끝나 있어야 한다.
    (crack/yolo는 여전히 무방비라 예외가 밖으로 전파될 수 있다 — 그건 `_warmup_defect_models()`가
    잡는다. 여기서 검증하는 것은 "임베딩이 그 전에 실행됐는가"다.)
    """
    defect_targets = {
        "ai.core.unet_client.get_crack_model": MagicMock(),
        "ai.core.yolo_client.get_yolo_model": MagicMock(),
        "ai.core.card_client.warmup_card_model": MagicMock(),
    }
    defect_targets[failing_target].side_effect = RuntimeError("체크포인트 다운로드 실패")
    mock_embeddings = MagicMock()

    with contextlib.ExitStack() as stack:
        for target, mock in defect_targets.items():
            stack.enter_context(patch(target, mock))
        stack.enter_context(patch("ai.core.embeddings.get_embeddings", mock_embeddings))
        # crack/yolo는 개별 격리가 없어 예외가 밖으로 나간다 — 그건 _warmup_defect_models()가 잡는다.
        with contextlib.suppress(RuntimeError):
            main._load_defect_models_sync()

    assert mock_embeddings.call_count == 1, (
        f"{failing_target} 실패로 임베딩 워밍업이 실행되지 않았다 — "
        "get_embeddings()가 하자 모델 로드보다 뒤에 있다는 뜻이다(#1594 코드리뷰 P2 회귀)"
    )


def test_embedding_warmup_runs_before_every_defect_model():
    """순서 자체를 고정한다 — 개별 try/except만 있고 순서가 되돌아가면, 앞선 모델이 예외 없이
    **오래 매달리는** 경우(느린 대용량 다운로드)에 임베딩 워밍업이 그만큼 밀린다.

    이전 버전은 `embeddings ↔ card`만 기록해 crack/yolo가 앞에 있는 것을 검출하지 못했다
    (코드리뷰 P2) — 네 개를 모두 기록해 임베딩이 **맨 앞**임을 고정한다.
    """
    call_order = []

    with patch(
        "ai.core.embeddings.get_embeddings", side_effect=lambda: call_order.append("embeddings")
    ), patch(
        "ai.core.unet_client.get_crack_model", side_effect=lambda: call_order.append("crack")
    ), patch(
        "ai.core.yolo_client.get_yolo_model", side_effect=lambda _t: call_order.append("yolo")
    ), patch(
        "ai.core.card_client.warmup_card_model", side_effect=lambda: call_order.append("card")
    ):
        main._load_defect_models_sync()

    assert call_order[0] == "embeddings", (
        f"임베딩 워밍업이 맨 앞이 아니다 — 실제 순서: {call_order}. 앞선 하자 모델이 실패하면 "
        "RAG 첫 호출이 콜드스타트를 떠안는다(#1594)"
    )
    assert call_order == ["embeddings", "crack", "yolo", "yolo", "card"]


def test_semantic_cache_retention_loop_runs_enforce_retention():
    """#1594 코드리뷰 P2 — 보존 정책이 **쓰기 경로에서만** 돌면 트래픽 저조·exact 캐시만 히트·
    개인회원만 접속하는 기간에 정리가 멈춰, 조회 필터가 가려줄 뿐 질문 원문 평문이 디스크에
    무기한 남는다. "TTL이 곧 평문 보존 상한"이라는 설계 판단은 주기 실행이 있어야 성립한다.

    `asyncio.sleep`을 가로채 첫 주기만 돌리고 루프를 끊는다(실제 1시간을 기다리지 않는다).
    """
    calls = []

    async def _fake_sleep(_seconds):
        if calls:  # 두 번째 주기 진입 시 루프 종료
            raise asyncio.CancelledError
        return None

    with patch("ai.core.semantic_cache.enforce_retention", side_effect=lambda: calls.append(1)), \
         patch("main.asyncio.sleep", _fake_sleep):
        with contextlib.suppress(asyncio.CancelledError):
            asyncio.run(main._semantic_cache_retention_loop())

    assert calls == [1]


def test_lifespan_starts_semantic_cache_retention_loop():
    """lifespan에 루프가 실제로 등록되는지 — 함수만 있고 기동에 안 붙으면 아무 효과가 없다.

    MagicMock이 아니라 실제 코루틴 함수로 대체한다 — mock의 반환값을 코루틴으로 세팅하면
    lifespan이 태스크를 취소할 때 그 코루틴이 await되지 않아 RuntimeWarning이 남는다.
    """
    started = []

    async def _fake_loop():
        started.append(1)
        await asyncio.Event().wait()  # 취소될 때까지 대기(실제 루프와 동일하게 장수명)

    with patch("main._semantic_cache_retention_loop", _fake_loop):
        with TestClient(main.app) as client:
            assert client.get("/health").status_code == 200

    assert started == [1], "lifespan이 시맨틱 캐시 보존 정책 주기 루프를 기동하지 않았다"


def test_warmup_defect_models_swallows_loader_exception():
    # 워밍업 실패(네트워크 장애·체크포인트 없음 등)로 앱 기동 자체가 죽으면 안 된다 —
    # 첫 실제 분석 요청이 get_yolo_model()/get_crack_model()의 지연 로드 경로로 재시도한다.
    with patch("main._load_defect_models_sync", side_effect=RuntimeError("HF Hub 연결 실패")):
        asyncio.run(main._warmup_defect_models())  # 예외가 여기서 전파되면 테스트 실패


def test_lifespan_skips_warmup_task_while_running_under_pytest():
    # PYTEST_CURRENT_TEST는 pytest가 테스트 실행 동안 항상 설정하므로, 이 테스트 자체가 그 가드를
    # 실제로 태운다 — 목이 호출 안 되면 lifespan이 워밍업 태스크를 아예 안 만들었다는 뜻이다.
    with patch("main._warmup_defect_models") as mock_warmup:
        with TestClient(main.app) as client:
            res = client.get("/health")
            assert res.status_code == 200

    mock_warmup.assert_not_called()
