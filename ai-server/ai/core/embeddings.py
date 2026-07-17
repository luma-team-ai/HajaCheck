"""임베딩 모델 설정 (ko-sbert / BGE-m3, CPU) — AI_개발_컨벤션.md §1"""
import os
from functools import lru_cache

from langchain_huggingface import HuggingFaceEmbeddings

DEFAULT_EMBEDDING_MODEL = "BAAI/bge-m3"


@lru_cache
def get_embeddings() -> HuggingFaceEmbeddings:
    # NOTE: `os.getenv`는 반드시 함수 **내부**에서 호출(모듈 임포트 시점 아님) — deps.py와 동일 규칙.
    # EMBEDDING_MODEL 오버라이드가 임포트 순서에 좌우되지 않도록 호출 시점에 읽는다.
    return HuggingFaceEmbeddings(
        model_name=os.getenv("EMBEDDING_MODEL", DEFAULT_EMBEDDING_MODEL),
        model_kwargs={"device": "cpu"},
    )
