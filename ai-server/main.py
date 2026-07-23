import os

from dotenv import load_dotenv
from fastapi import FastAPI

load_dotenv()

from routers.ai_router import router as ai_router  # noqa: E402 — load_dotenv() 이후 임포트 필요
from routers.nl_search_router import router as nl_search_router  # noqa: E402

# fail-closed: 명시적 dev/local일 때만 /docs·/redoc·/openapi.json 노출, 그 외(미설정·prod·오타 등 전부)는 차단
# (내부 AI 실행은 deps.py 내부키로 이미 보호되지만, 스키마 문서 자체는 별도 라우트라 추가로 막아야 함.
# APP_ENV 기본값을 "dev"로 두면 prod 신호를 빠뜨린 배포 경로에서 노출되는 fail-open이 되므로 화이트리스트 방식으로 뒤집는다).
_docs_enabled = os.getenv("APP_ENV", "").strip().lower() in {"dev", "local"}

app = FastAPI(
    title="hajaCheck AI Server",
    docs_url="/docs" if _docs_enabled else None,
    redoc_url="/redoc" if _docs_enabled else None,
    openapi_url="/openapi.json" if _docs_enabled else None,
)

app.include_router(ai_router)
app.include_router(nl_search_router)


@app.get("/health")
def health():
    return {"status": "ok"}
