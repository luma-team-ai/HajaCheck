"""regulations 컬렉션 하이브리드 검색(벡터 + BM25, RRF 결합) — #1410.

법규 조문은 정확한 용어·조번호 매칭이 중요해, 순수 벡터 유사도(top-k)만으로는 "제12조" 같은
키워드 검색에 약하다. 벡터검색과 BM25 키워드검색을 각각 수행해 Reciprocal Rank Fusion(RRF)으로
결합한다. 반환값은 rag_chat_chain._build_sources/_build_context가 무수정으로 쓸 수 있도록
similarity_search()와 동일한 `list[Document]` 형태를 유지한다.

os.getenv는 반드시 함수 호출 시점에 읽는다(vectorstore.py와 동일 원칙 — 테스트가
patch.dict(os.environ, ...)로 주입해도 반영되도록).
"""
from __future__ import annotations

import os

from langchain_core.documents import Document

from ai.core import bm25_index
from ai.core.vectorstore import COLLECTION_REGULATIONS, get_vectorstore

DEFAULT_TOP_K = 4
DEFAULT_VECTOR_TOP_K = 10
DEFAULT_BM25_TOP_K = 10
DEFAULT_RRF_K = 60


def _chunk_ref(metadata: dict) -> str:
    # rag_ingest._chunk_document_id와 동일 규칙("{doc_id}_{chunk_index}") — 여기서 문서 동일성
    # 판정 기준으로 재사용한다. Chroma id 자체(스냅샷 doc_ids)와 반드시 일치해야 한다.
    return f"{metadata.get('doc_id')}_{metadata.get('chunk_index')}"


def _vector_search(query: str, vector_k: int) -> list[Document]:
    return get_vectorstore(COLLECTION_REGULATIONS).similarity_search(query, k=vector_k)


def _bm25_search(query: str, bm25_k: int) -> list[Document]:
    snapshot = bm25_index.get_snapshot(COLLECTION_REGULATIONS)
    if not snapshot.documents:
        return []

    tokenized_query = bm25_index._tokenize(query)
    scores = snapshot.bm25.get_scores(tokenized_query)

    ranked_indices = sorted(range(len(scores)), key=lambda i: scores[i], reverse=True)
    top_indices = ranked_indices[:bm25_k]

    docs: list[Document] = []
    for i in top_indices:
        docs.append(
            Document(page_content=snapshot.documents[i], metadata=dict(snapshot.metadatas[i]))
        )
    return docs


def _reciprocal_rank_fusion(
    ranked_lists: list[list[Document]],
    rrf_k: int,
    top_k: int,
) -> list[Document]:
    """RRF로 여러 랭킹 리스트를 결합한다. score(d) = Σ 1/(rrf_k + rank_i(d))(rank는 0-base).

    문서 동일성은 _chunk_ref(metadata)로 판정한다. 한쪽 리스트에만 있는 문서도 포함(합집합).
    정렬은 점수 내림차순, 동점 시 리스트 순서상 앞선(=먼저 나열된, 관례상 벡터가 0번) 랭킹의
    순위를 안정적으로 우선한다 — Python sorted()가 stable sort이므로, 입력 순서(첫 리스트에서
    먼저 나온 문서가 앞) 자체가 이미 그 tie-break 역할을 한다."""
    scores: dict[str, float] = {}
    doc_by_ref: dict[str, Document] = {}
    first_seen_order: dict[str, int] = {}
    order_counter = 0

    for ranked_list in ranked_lists:
        for rank, doc in enumerate(ranked_list):
            ref = _chunk_ref(doc.metadata)
            scores[ref] = scores.get(ref, 0.0) + 1.0 / (rrf_k + rank)
            if ref not in doc_by_ref:
                doc_by_ref[ref] = doc
                first_seen_order[ref] = order_counter
                order_counter += 1

    ordered_refs = sorted(
        doc_by_ref.keys(),
        key=lambda ref: (-scores[ref], first_seen_order[ref]),
    )
    return [doc_by_ref[ref] for ref in ordered_refs[:top_k]]


def hybrid_search(
    query: str,
    k: int | None = None,
    vector_k: int | None = None,
    bm25_k: int | None = None,
    rrf_k: int | None = None,
) -> list[Document]:
    """벡터검색 + BM25검색을 RRF로 결합해 상위 k개 Document를 반환한다."""
    if k is None:
        k = int(os.getenv("RAG_CHAT_TOP_K", str(DEFAULT_TOP_K)))
    if vector_k is None:
        vector_k = int(os.getenv("RAG_HYBRID_VECTOR_TOP_K", str(DEFAULT_VECTOR_TOP_K)))
    if bm25_k is None:
        bm25_k = int(os.getenv("RAG_HYBRID_BM25_TOP_K", str(DEFAULT_BM25_TOP_K)))
    if rrf_k is None:
        rrf_k = int(os.getenv("RAG_HYBRID_RRF_K", str(DEFAULT_RRF_K)))

    vector_docs = _vector_search(query, vector_k)
    bm25_docs = _bm25_search(query, bm25_k)

    return _reciprocal_rank_fusion([vector_docs, bm25_docs], rrf_k=rrf_k, top_k=k)
