"""Chroma PersistentClient 팩토리 — AI_개발_컨벤션.md §6

- Chroma 접근은 이 팩토리만 사용
- 컬렉션: regulations(법규·지침), defect_kb(하자 지식)
- 재임베딩은 명시적 배치 잡으로만 실행 (쓰기 락 충돌 방지)
"""
import os
from functools import lru_cache

import chromadb
from chromadb import Collection
from chromadb.api.types import Documents, EmbeddingFunction, Embeddings

from ai.core.embeddings import get_embeddings

COLLECTION_REGULATIONS = "regulations"
COLLECTION_DEFECT_KB = "defect_kb"

# docker-compose 볼륨(chroma_data:/app/chroma_data)과 일치 — 로컬 실행 시 오버라이드
DEFAULT_CHROMA_PERSIST_DIR = "/app/chroma_data"


class _LangChainEmbeddingFunction(EmbeddingFunction):
    """embeddings.py의 LangChain Embeddings(.embed_documents)를 chromadb EmbeddingFunction으로 어댑팅.

    chromadb는 컬렉션 생성/재오픈 시 embedding_function의 name()/config로 등록·검증하므로
    __call__ 외에 name()/get_config()/build_from_config()까지 구현해야 실 런타임에서 동작한다.
    """

    def __init__(self) -> None:
        pass

    def __call__(self, input: Documents) -> Embeddings:
        return get_embeddings().embed_documents(list(input))

    @staticmethod
    def name() -> str:
        return "hajacheck_langchain"

    def get_config(self) -> dict:
        # 모델 설정은 get_embeddings()(EMBEDDING_MODEL env)가 소유 — 여기선 식별용 빈 config로 충분
        return {}

    @staticmethod
    def build_from_config(config: dict) -> "_LangChainEmbeddingFunction":
        return _LangChainEmbeddingFunction()


@lru_cache
def _client() -> chromadb.ClientAPI:
    # NOTE: `os.getenv`는 반드시 함수 **내부**에서 호출(모듈 임포트 시점 아님) — deps.py와 동일 규칙.
    # 모듈 최상단에서 읽으면 값이 첫 임포트 시점에 고정돼, 테스트가 patch.dict(os.environ, ...)로
    # 주입해도 반영되지 않는다(임포트 순서에 따라 통과/실패가 갈림).
    return chromadb.PersistentClient(
        path=os.getenv("CHROMA_PERSIST_DIR", DEFAULT_CHROMA_PERSIST_DIR)
    )


def get_vectorstore(collection: str) -> Collection:
    if collection not in (COLLECTION_REGULATIONS, COLLECTION_DEFECT_KB):
        raise ValueError(f"unknown collection: {collection}")
    return _client().get_or_create_collection(
        name=collection,
        embedding_function=_LangChainEmbeddingFunction(),
        # BGE 계열 임베딩은 코사인 유사도 기준으로 학습됨 — chromadb 기본(L2)과 다르므로 명시
        metadata={"hnsw:space": "cosine"},
    )
