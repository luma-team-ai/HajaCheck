import os

from dotenv import load_dotenv
from fastapi import FastAPI

load_dotenv()

from routers.ai_router import router as ai_router  # noqa: E402 — load_dotenv() 이후 임포트 필요

# 프로덕션(APP_ENV=prod)에서는 /docs·/redoc·/openapi.json을 비활성화해 무인증 스키마 노출을 막는다
# (내부 AI 실행은 deps.py 내부키로 이미 보호되지만, 스키마 문서 자체는 별도 라우트라 추가로 막아야 함).
_is_prod = os.getenv("APP_ENV", "dev").lower() == "prod"

app = FastAPI(
    title="hajaCheck AI Server",
    docs_url=None if _is_prod else "/docs",
    redoc_url=None if _is_prod else "/redoc",
    openapi_url=None if _is_prod else "/openapi.json",
)

app.include_router(ai_router)


@app.get("/health")
def health():
    return {"status": "ok"}
