import asyncio
import contextlib
import logging
import os

from dotenv import load_dotenv
from fastapi import FastAPI

load_dotenv()

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


def _load_yolo_model_sync() -> None:
    # 지연 임포트 — ultralytics/huggingface_hub는 무거운 의존성이라 모듈 최상단에서 임포트하면
    # main.py를 import하는 모든 테스트(TestClient 미사용 포함)가 그 비용을 진다.
    from ai.core.yolo_client import get_yolo_model

    get_yolo_model()


async def _warmup_yolo_model() -> None:
    """YOLO 모델을 앱 기동 시 백그라운드로 미리 로드한다(코드 리뷰 P2, 사용자 확인 완료).

    get_yolo_model()은 `@lru_cache`라 최초 호출이 HF Hub 체크포인트 다운로드(콜드스타트, 네트워크·
    캐시 상태에 따라 수 분)를 동반한다. 미리 로드해두지 않으면 배포 직후 첫 실제 분석 요청이 이
    다운로드를 그대로 떠안고, 그 시간이 백엔드 하트비트 임계값(InspectionAnalysisService.
    STUCK_HEARTBEAT_THRESHOLD, 5분)을 넘기면 정상 진행 중인 잡을 고착으로 오판해 이중 워커 실행까지
    이어질 수 있다(#701 코드 리뷰).

    `/health`나 다른 엔드포인트를 막지 않도록 백그라운드 태스크로 돌린다(_purge_llm_cache_loop와
    동일 패턴) — 컨테이너 헬스체크 start_period(20s)를 모델 다운로드 시간에 맞춰 늘릴 필요가 없다.
    """
    try:
        await asyncio.to_thread(_load_yolo_model_sync)
        logger.info("YOLO 모델 워밍업 완료")
    except Exception:  # noqa: BLE001 — 워밍업 실패해도 앱은 계속 뜬다(첫 실제 요청에서 지연 로드로 재시도)
        logger.exception("YOLO 모델 워밍업 실패 — 첫 실제 분석 요청에서 지연 로드로 재시도된다")


@contextlib.asynccontextmanager
async def lifespan(_app: FastAPI):
    task = asyncio.create_task(_purge_llm_cache_loop())
    # pytest 실행 중에는 워밍업을 건너뛴다 — _purge_llm_cache_loop는 첫 sleep이 24시간이라 테스트
    # 중 자연히 무해하지만, 이 태스크는 곧바로 실제 네트워크 I/O(HF Hub)를 시도해 같은 보호를 못
    # 받는다. TestClient(main.app)를 쓰는 여러 테스트 파일이 patch 없이도 lifespan을 그대로 타므로,
    # 가드가 없으면 그런 테스트 전부가 느려지고 네트워크에 의존하게 된다.
    warmup_task = None if "PYTEST_CURRENT_TEST" in os.environ else asyncio.create_task(_warmup_yolo_model())
    try:
        yield
    finally:
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
