"""Chroma PersistentClient 팩토리 — AI_개발_컨벤션.md §6

- Chroma 접근은 이 팩토리만 사용
- 컬렉션: regulations(법규·지침), defect_kb(하자 지식)
- 재임베딩은 명시적 배치 잡으로만 실행 (쓰기 락 충돌 방지)
- get_vectorstore()는 LangChain Chroma 객체 반환 (similarity_search, add_texts 인터페이스)
"""
import os
from functools import lru_cache

import chromadb
from langchain_chroma import Chroma

from ai.core.embeddings import get_embeddings

COLLECTION_REGULATIONS = "regulations"
COLLECTION_DEFECT_KB = "defect_kb"

# docker-compose 볼륨(chroma_data:/app/chroma_data)과 일치 — 로컬 실행 시 오버라이드
DEFAULT_CHROMA_PERSIST_DIR = "/app/chroma_data"


@lru_cache
def _client() -> chromadb.ClientAPI:
    # NOTE: `os.getenv`는 반드시 함수 **내부**에서 호출(모듈 임포트 시점 아님) — deps.py와 동일 규칙.
    # 모듈 최상단에서 읽으면 값이 첫 임포트 시점에 고정돼, 테스트가 patch.dict(os.environ, ...)로
    # 주입해도 반영되지 않는다(임포트 순서에 따라 통과/실패가 갈림).
    return chromadb.PersistentClient(
        path=os.getenv("CHROMA_PERSIST_DIR", DEFAULT_CHROMA_PERSIST_DIR)
    )


def get_vectorstore(collection: str) -> Chroma:
    """LangChain Chroma 래퍼 반환.

    similarity_search(query, k=N) / add_texts() / add_documents() 등
    LangChain VectorStore 인터페이스를 제공한다.
    """
    if collection not in (COLLECTION_REGULATIONS, COLLECTION_DEFECT_KB):
        raise ValueError(f"unknown collection: {collection}")
    # BGE 계열 임베딩은 코사인 유사도 기준으로 학습됨 — chromadb 기본(L2)과 다르므로 명시
    return Chroma(
        client=_client(),
        collection_name=collection,
        embedding_function=get_embeddings(),
        collection_metadata={"hnsw:space": "cosine"},
    )
