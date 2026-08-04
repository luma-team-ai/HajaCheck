import asyncio
import contextlib
import logging
import os

from dotenv import load_dotenv
from fastapi import FastAPI

load_dotenv()

from ai.core.langsmith_pii_scrub import install_pii_scrub_anonymizer  # noqa: E402

# LangSmith 트레이스 PII 정규식 스크럽(#1585) — 첫 트레이스가 만들어지기 전에 anonymizer를
# 선점 설치해야 효과가 있다. 라우터(체인 임포트로 이어짐) 임포트보다 반드시 먼저 호출한다.
# 여기서 try/except로 감싸지 않는 이유 — 함수 자체가 내부에서 모든 예외를 삼키고
# logger.exception만 남기는 fail-safe 계약이다(설치 실패가 앱 기동을 막으면 안 된다).
# 그 계약은 test_langsmith_pii_scrub.py의 install fail-safe 테스트가 고정한다.
install_pii_scrub_anonymizer()

from routers.ai_router import router as ai_router  # noqa: E402 — load_dotenv() 이후 임포트 필요
from routers.nl_search_router import router as nl_search_router  # noqa: E402

logger = logging.getLogger(__name__)

# LLM structured 응답 캐시 주기 purge (#623) — ai.core.llm_client.CachedLLM/_StructuredLLM이
# Redis에 쌓는 `ai:cache:*` 캐시 항목을 24h마다 일괄 삭제한다. 공유 Redis(OCI dev/arm1 prod)에
# OCR 응답 등 개인정보가 잔존하는 기간을 줄이기 위함 — TTL(setex)만으로도 결국 만료되지만,
# 명시적 주기 purge로 상한을 이중으로 건다.
CACHE_PURGE_INTERVAL_SECONDS = 60 * 60 * 24


def _purge_llm_cache_once() -> int:
    """`ai:cache:*` 키만 scan_iter로 찾아 delete하고 삭제 건수를 반환한다.

    `ai:usage:*`(일별 토큰 사용량 집계, 관리자 모니터링 연동) 키는 절대 건드리지 않는다 —
    flushdb는 전체 DB를 지워 사용량 집계까지 날리므로 반드시 scan_iter+delete만 사용한다.
    함수를 분리해 lifespan 루프 없이도(테스트 등) 단위 호출로 검증 가능하게 한다.
    """
    from ai.core.llm_client import _redis  # 지연 임포트 — REDIS_URL은 _redis() 호출 시점에 읽힘

    client = _redis()
    keys = list(client.scan_iter(match="ai:cache:*"))
    if keys:
        client.delete(*keys)
    return len(keys)


async def _purge_llm_cache_loop() -> None:
    """CACHE_PURGE_INTERVAL_SECONDS마다 `_purge_llm_cache_once()`를 실행하는 백그라운드 루프.

    redis 오류는 로깅만 하고 삼켜 앱을 죽이지 않는다(사용량 집계와 동일한 원칙 —
    캐시 purge 실패가 서비스 응답을 막으면 안 됨). 멀티워커 환경에서 워커마다 이 루프가
    각자 돌아 중복 실행되더라도 delete는 멱등이라 무해하다(스케줄러 라이브러리 추가 없이
    asyncio만으로 충분 — 과설계 지양).
    """
    while True:
        await asyncio.sleep(CACHE_PURGE_INTERVAL_SECONDS)
        try:
            deleted = _purge_llm_cache_once()
            logger.info("LLM 캐시 주기 purge 완료 — %d건 삭제", deleted)
        except Exception:  # noqa: BLE001 — redis 장애로 앱이 죽으면 안 됨
            logger.exception("LLM 캐시 주기 purge 실패 — 다음 주기에 재시도")


# RAG 시맨틱 캐시 보존 정책 주기 실행 간격(#1594) — TTL 경과분·레거시 항목 삭제, 용량 초과 축출.
# `_purge_llm_cache_loop`(24h)보다 짧은 1시간으로 둔다: 그쪽은 TTL(setex)이 Redis 차원에서 이미
# 만료를 보장하는 이중 안전장치라 주기가 느슨해도 되지만, Chroma에는 만료 개념이 없어 이 루프가
# **유일한 시간 기반 삭제 수단**이다(조회 필터는 노출만 막을 뿐 디스크의 평문은 그대로다).
SEMANTIC_CACHE_RETENTION_INTERVAL_SECONDS = 60 * 60


async def _semantic_cache_retention_loop() -> None:
    """SEMANTIC_CACHE_RETENTION_INTERVAL_SECONDS마다 `enforce_retention()`을 실행하는 루프.

    저장 경로(`_node_semantic_cache_write`)에도 같은 호출이 붙어 있지만 그것만으로는 부족하다
    (#1594 코드리뷰 P2) — 트래픽이 저조하거나, exact(Redis) 캐시만 히트하거나, 회사 미소속
    개인회원만 접속하면 시맨틱 캐시 쓰기가 일어나지 않아 정리도 함께 멈춘다. 그러면 조회 필터가
    가려줄 뿐 **질문 원문 평문은 디스크에 무기한 남아**, "TTL이 곧 평문 보존 상한"이라는
    semantic_cache.py 설계 판단 1의 단언이 성립하지 않는다.

    `enforce_retention()`이 내부에서 예외를 삼키지만, 지연 임포트 실패 등 그 바깥의 사고까지
    루프를 죽이지 않도록 여기서도 한 번 더 감싼다(_purge_llm_cache_loop와 동일 원칙).
    멀티워커에서 워커마다 돌아도 삭제는 멱등이라 무해하다.
    """
    while True:
        await asyncio.sleep(SEMANTIC_CACHE_RETENTION_INTERVAL_SECONDS)
        try:
            # 지연 임포트 — semantic_cache는 vectorstore/chromadb를 끌어오므로 모듈 최상단에서
            # 임포트하면 main.py를 import하는 모든 테스트가 그 비용을 진다(_purge_llm_cache_once와 동일).
            from ai.core.semantic_cache import enforce_retention

            enforce_retention()
            logger.info("시맨틱 캐시 보존 정책 주기 적용 완료")
        except Exception:  # noqa: BLE001 — 캐시 정리 실패로 앱이 죽으면 안 됨
            logger.exception("시맨틱 캐시 보존 정책 주기 적용 실패 — 다음 주기에 재시도")


def _load_defect_models_sync() -> None:
    # 지연 임포트 — ultralytics/huggingface_hub/segmentation_models_pytorch는 무거운 의존성이라
    # 모듈 최상단에서 임포트하면 main.py를 import하는 모든 테스트(TestClient 미사용 포함)가 그
    # 비용을 진다.
    from ai.core.embeddings import get_embeddings
    from ai.core.unet_client import get_crack_model
    from ai.core.yolo_client import get_yolo_model

    # ⚠️ 순서 주의(#1594) — bge-m3 임베딩 워밍업이 **맨 앞**이다. 뒤따르는 하자 탐지 모델 4종은
    # 전부 런타임 HF Hub/GitHub 다운로드 경로라(unet_client.py·yolo_client.py·card_client.py) 어느
    # 하나라도 예외를 던지면, 개별 try/except가 없는 한 _warmup_defect_models()의 바깥 핸들러까지
    # 튕겨 **뒤에 있는 호출이 통째로 실행되지 않는다.** get_embeddings()가 뒤에 있으면 부가 기능
    # (하자 탐지) 실패가 기존 기능(RAG 첫 호출)의 콜드스타트 회귀로 그대로 번진다.
    #
    # 처음 픽스는 카드 모델만 뒤로 보냈으나, 그 앞의 crack/yolo 3종도 같은 다운로드 경로라 실패
    # 모드가 "카드" → "U-Net/YOLO"로 이름만 바뀐 채 남아 있었다(코드리뷰 P2). 맨 앞으로 올려
    # 어떤 하자 모델이 실패하든 임베딩 워밍업은 이미 끝나 있게 한다.
    get_embeddings()

    # 유형별 전용 체크포인트 3개(2026-07-27, 6차 rebase — yolo_client.py 모듈 docstring 참고) —
    # 하나라도 워밍업에서 빠지면 그 유형의 첫 실제 분석 요청이 콜드스타트 다운로드를 그대로 떠안는다.
    get_crack_model()
    get_yolo_model("SPALLING")
    get_yolo_model("REBAR_EXPOSURE")

    # 카드 검출(#1487, #1547 머신 검수 P1) — YOLO-World 가중치 24.7MB + set_classes()가 받는
    # CLIP ViT-B/32 338MB. 균열이 검출된 사진에서만 타는 부가 경로지만 콜드스타트 비용은 위
    # 3종보다 오히려 크므로 동일하게 미리 로드한다.
    #
    # 개별 try/except로 감싼다(#1594) — defect_detection_chain.py의 detect_card 호출부가 이미 쓰는
    # 패턴과 동일하다("카드는 부가 정보라, 그 실패가 균열 탐지 자체를 무너뜨리면 안 된다"). 지연
    # 임포트까지 블록 안에 두는 이유는 ultralytics/CLIP 의존성 임포트 자체가 실패할 수 있어서다.
    # (crack/yolo 3종은 기존대로 무방비 — 하자 탐지끼리는 서로 인질이 되어도 같은 기능군이라
    # 판단 근거가 다르다. 임베딩만 그 바깥으로 빼내는 것이 이 픽스의 요지다.)
    try:
        from ai.core.card_client import warmup_card_model

        warmup_card_model()
    except Exception:  # noqa: BLE001 — 카드 워밍업 실패는 첫 실제 요청의 지연 로드로 재시도된다
        logger.exception("카드 검출 모델 워밍업 실패 — 다른 모델 워밍업은 계속 진행한다 (#1594)")


async def _warmup_defect_models() -> None:
    """하자 탐지 모델(크랙 U-Net·YOLO 2종·카드 검출)을 앱 기동 시 백그라운드로 미리 로드한다
    (코드 리뷰 P2, 사용자 확인 완료).

    get_crack_model()/get_yolo_model()/warmup_card_model()은 각각 `@lru_cache`라 최초 호출이
    체크포인트 다운로드(콜드스타트, 네트워크·캐시 상태에 따라 수 분)를 동반한다. 미리 로드해두지
    않으면 배포 직후 첫 실제 분석 요청이 이 다운로드를 그대로 떠안고, 그 시간이 백엔드 하트비트
    임계값(InspectionAnalysisService.STUCK_HEARTBEAT_THRESHOLD)을 넘기면 정상 진행 중인 잡을
    고착으로 오판해 이중 워커 실행까지 이어질 수 있다(#701 코드 리뷰).
    (이 문서의 임계값은 한때 "5분"으로 적혀 있었으나 실제 상수는 15분이다 — #1547 검수에서
    이 stale 값이 리뷰 판단을 오도해 정정. 값 자체는 백엔드가 진실 소스이니 그쪽을 볼 것.)

    `/health`나 다른 엔드포인트를 막지 않도록 백그라운드 태스크로 돌린다(_purge_llm_cache_loop와
    동일 패턴) — 컨테이너 헬스체크 start_period(20s)를 모델 다운로드 시간에 맞춰 늘릴 필요가 없다.
    """
    try:
        await asyncio.to_thread(_load_defect_models_sync)
        logger.info("하자 탐지 모델 워밍업 완료")
    except Exception:  # noqa: BLE001 — 워밍업 실패해도 앱은 계속 뜬다(첫 실제 요청에서 지연 로드로 재시도)
        logger.exception("하자 탐지 모델 워밍업 실패 — 첫 실제 분석 요청에서 지연 로드로 재시도된다")


@contextlib.asynccontextmanager
async def lifespan(_app: FastAPI):
    # 주기 유지보수 루프들 — 둘 다 첫 대기가 길어(24h/1h) 테스트 중에는 자연히 아무 일도 하지 않는다.
    background_tasks = [
        asyncio.create_task(_purge_llm_cache_loop()),
        asyncio.create_task(_semantic_cache_retention_loop()),  # #1594
    ]
    # pytest 실행 중에는 워밍업을 건너뛴다 — _purge_llm_cache_loop는 첫 sleep이 24시간이라 테스트
    # 중 자연히 무해하지만, 이 태스크는 곧바로 실제 네트워크 I/O(HF Hub)를 시도해 같은 보호를 못
    # 받는다. TestClient(main.app)를 쓰는 여러 테스트 파일이 patch 없이도 lifespan을 그대로 타므로,
    # 가드가 없으면 그런 테스트 전부가 느려지고 네트워크에 의존하게 된다.
    warmup_task = None if "PYTEST_CURRENT_TEST" in os.environ else asyncio.create_task(_warmup_defect_models())
    try:
        yield
    finally:
        for task in background_tasks:
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await task
        if warmup_task is not None:
            warmup_task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await warmup_task


# fail-closed: 명시적 dev/local일 때만 /docs·/redoc·/openapi.json 노출, 그 외(미설정·prod·오타 등 전부)는 차단
# (내부 AI 실행은 deps.py 내부키로 이미 보호되지만, 스키마 문서 자체는 별도 라우트라 추가로 막아야 함.
# APP_ENV 기본값을 "dev"로 두면 prod 신호를 빠뜨린 배포 경로에서 노출되는 fail-open이 되므로 화이트리스트 방식으로 뒤집는다).
_docs_enabled = os.getenv("APP_ENV", "").strip().lower() in {"dev", "local"}

app = FastAPI(
    title="hajaCheck AI Server",
    docs_url="/docs" if _docs_enabled else None,
    redoc_url="/redoc" if _docs_enabled else None,
    openapi_url="/openapi.json" if _docs_enabled else None,
    lifespan=lifespan,
)

app.include_router(ai_router)
app.include_router(nl_search_router)


@app.get("/health")
def health():
    return {"status": "ok"}
