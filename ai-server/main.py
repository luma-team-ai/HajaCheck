from dotenv import load_dotenv
from fastapi import FastAPI

load_dotenv()

from routers.ai_router import router as ai_router  # noqa: E402 — load_dotenv() 이후 임포트 필요

app = FastAPI(title="hajaCheck AI Server", docs_url="/docs")

app.include_router(ai_router)


@app.get("/health")
def health():
    return {"status": "ok"}
