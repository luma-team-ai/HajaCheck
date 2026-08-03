"""regulations 컬렉션 전용 BM25 인메모리 인덱스 — 하이브리드 검색(#1410).

Chroma가 유일한 source of truth이고, 이 모듈은 그 위에 얹는 **파생 캐시**일 뿐이다(영속화하지
않으며, 프로세스 재시작 시 처음 조회될 때 다시 구축된다). ingest/delete로 Chroma 내용이 바뀌면
invalidate()를 호출해 다음 조회 시 재구축되도록 하는 것만으로 정합성을 유지한다 — 캐시를
직접 갱신(add/remove)하지 않는다(구현 단순성, Chroma 전체 재조회가 규모상 충분히 저렴하다는
전제 — regulations는 법규 조문이라 defect_kb 대비 문서량이 작다).
"""
from __future__ import annotations

import threading
from dataclasses import dataclass, field

from kiwipiepy import Kiwi
from rank_bm25 import BM25Okapi

from ai.core.vectorstore import COLLECTION_REGULATIONS, get_vectorstore

# 정규식(공백/음절 단위) 토큰화는 한국어 조사·어미가 붙은 채로 토큰이 갈려("안전점검을" ≠ "안전점검")
# BM25 recall이 크게 떨어짐을 #1410 실측(law.go.kr 공식 법규 PDF 7건 적재 후 22~27개 질의 비교)으로
# 확인했다 — 정규식 토큰화 하이브리드는 vector-only보다 MRR/nDCG가 낮았고, kiwipiepy 형태소
# 분석기로 교체하니 vector-only가 top-10에서 완전히 놓치는 질의(예: "별표 N" 참조형 질의)까지
# BM25가 복구해 recall@10/MRR/nDCG가 전부 개선됐다. Kiwi 인스턴스는 프로세스 전역 재사용(생성
# 비용이 크므로 매 호출 생성 금지).
_kiwi = Kiwi()
_CONTENT_TAGS = ("N", "V", "SL", "SN")  # 체언(N*)·용언(V*)·외국어·숫자만 — 조사/어미/구두점 제외


def _tokenize(text: str) -> list[str]:
    """kiwipiepy 형태소 분석으로 체언·용언·외국어·숫자 토큰만 추출한다(조사·어미·구두점 제외)."""
    if not text:
        return []
    return [t.form for t in _kiwi.tokenize(text) if t.tag.startswith(_CONTENT_TAGS)]


@dataclass
class Bm25Snapshot:
    """특정 시점 Chroma 컬렉션 전체로부터 구축한 BM25 인덱스 스냅샷.

    bm25 코퍼스 순서와 doc_ids/documents/metadatas의 인덱스 순서가 반드시 일치한다."""

    bm25: BM25Okapi
    doc_ids: list[str]
    documents: list[str]
    metadatas: list[dict] = field(default_factory=list)


_snapshots: dict[str, Bm25Snapshot] = {}
_guard = threading.Lock()


def _build(collection: str) -> Bm25Snapshot:
    result = get_vectorstore(collection)._collection.get(include=["documents", "metadatas"])
    doc_ids = list(result.get("ids") or [])
    documents = list(result.get("documents") or [])
    metadatas = list(result.get("metadatas") or [])

    tokenized_corpus = [_tokenize(doc) for doc in documents]
    # BM25Okapi는 빈 코퍼스에도 생성 자체는 되지만 get_scores가 무의미해진다 — 빈 문서 리스트를
    # 그대로 넘겨도 예외는 나지 않으므로 방어 분기 없이 그대로 구성한다.
    bm25 = BM25Okapi(tokenized_corpus) if tokenized_corpus else BM25Okapi([[]])

    return Bm25Snapshot(bm25=bm25, doc_ids=doc_ids, documents=documents, metadatas=metadatas)


def get_snapshot(collection: str = COLLECTION_REGULATIONS) -> Bm25Snapshot:
    """캐시된 스냅샷을 반환한다. 없으면 double-checked locking으로 재구축한다."""
    snapshot = _snapshots.get(collection)
    if snapshot is not None:
        return snapshot

    with _guard:
        snapshot = _snapshots.get(collection)
        if snapshot is None:
            snapshot = _build(collection)
            _snapshots[collection] = snapshot
        return snapshot


def invalidate(collection: str = COLLECTION_REGULATIONS) -> None:
    """캐시를 비운다 — 다음 get_snapshot() 호출 시 Chroma에서 재구축된다.

    regulations가 아닌 컬렉션은 이 모듈이 다루는 대상이 아니므로 no-op이다(defect_kb 등
    캐시하지 않는 컬렉션에 대해 호출해도 안전하게 무시된다)."""
    if collection != COLLECTION_REGULATIONS:
        return
    with _guard:
        _snapshots.pop(collection, None)
