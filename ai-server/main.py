from fastapi import FastAPI

from routers.ai_router import router as ai_router

app = FastAPI(title="hajaCheck AI Server", docs_url="/docs")

app.include_router(ai_router)


@app.get("/health")
def health():
    return {"status": "ok"}
