"""임베딩 모델 설정 (ko-sbert / BGE-m3, CPU) — AI_개발_컨벤션.md §1"""
import os
from functools import lru_cache

from langchain_huggingface import HuggingFaceEmbeddings

DEFAULT_EMBEDDING_MODEL = os.getenv("EMBEDDING_MODEL", "BAAI/bge-m3")


@lru_cache
def get_embeddings() -> HuggingFaceEmbeddings:
    return HuggingFaceEmbeddings(
        model_name=DEFAULT_EMBEDDING_MODEL,
        model_kwargs={"device": "cpu"},
    )
