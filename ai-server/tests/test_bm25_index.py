"""ai.core.bm25_index 검증 — regulations 전용 BM25 인메모리 캐시(#1410).

실제 Chroma를 쓰지 않고 get_vectorstore()만 모킹해 `_collection.get()` 호출 결과로부터
BM25Okapi가 올바르게 구축되는지, 캐시 재사용/무효화가 의도대로 동작하는지 검증한다.
"""
import threading
from unittest.mock import MagicMock, patch

from ai.core import bm25_index
from ai.core.vectorstore import COLLECTION_DEFECT_KB, COLLECTION_REGULATIONS


def setup_function(_fn):
    # 모듈 전역 캐시가 테스트 간 공유되므로 매 테스트 시작 전 비운다.
    bm25_index._snapshots.clear()


def _mock_vectorstore(documents, metadatas, ids=None):
    mock_vs = MagicMock()
    mock_vs._collection.get.return_value = {
        "ids": ids or [f"doc_{i}" for i in range(len(documents))],
        "documents": documents,
        "metadatas": metadatas,
    }
    return mock_vs


def test_tokenize_한글과영숫자토큰을포함():
    tokens = bm25_index._tokenize("안전점검 abc123 실시하여야 한다")
    assert "안전점검" in tokens
    assert "abc123" in tokens
    assert "실시하여야" in tokens
    assert "한다" in tokens


def test_tokenize_빈문자열은빈리스트():
    assert bm25_index._tokenize("") == []
    assert bm25_index._tokenize(None) == []


@patch("ai.core.bm25_index.get_vectorstore")
def test_get_snapshot_chroma에서읽어bm25를구성(mock_get_vectorstore):
    mock_get_vectorstore.return_value = _mock_vectorstore(
        documents=["관리주체는 안전점검을 실시하여야 한다.", "정밀안전진단은 5년마다 실시한다."],
        metadatas=[{"doc_id": "1", "chunk_index": 0}, {"doc_id": "1", "chunk_index": 1}],
        ids=["1_0", "1_1"],
    )

    snapshot = bm25_index.get_snapshot(COLLECTION_REGULATIONS)

    mock_get_vectorstore.assert_called_once_with(COLLECTION_REGULATIONS)
    assert snapshot.doc_ids == ["1_0", "1_1"]
    assert len(snapshot.documents) == 2
    assert len(snapshot.metadatas) == 2
    assert snapshot.bm25 is not None


@patch("ai.core.bm25_index.get_vectorstore")
def test_get_snapshot_두번째호출은캐시재사용(mock_get_vectorstore):
    mock_get_vectorstore.return_value = _mock_vectorstore(
        documents=["관리주체는 안전점검을 실시하여야 한다."],
        metadatas=[{"doc_id": "1", "chunk_index": 0}],
        ids=["1_0"],
    )

    first = bm25_index.get_snapshot(COLLECTION_REGULATIONS)
    second = bm25_index.get_snapshot(COLLECTION_REGULATIONS)

    mock_get_vectorstore.assert_called_once()
    assert first is second


@patch("ai.core.bm25_index.get_vectorstore")
def test_invalidate_후재조회시재구축(mock_get_vectorstore):
    mock_get_vectorstore.return_value = _mock_vectorstore(
        documents=["관리주체는 안전점검을 실시하여야 한다."],
        metadatas=[{"doc_id": "1", "chunk_index": 0}],
        ids=["1_0"],
    )

    first = bm25_index.get_snapshot(COLLECTION_REGULATIONS)
    bm25_index.invalidate(COLLECTION_REGULATIONS)
    second = bm25_index.get_snapshot(COLLECTION_REGULATIONS)

    assert mock_get_vectorstore.call_count == 2
    assert first is not second


@patch("ai.core.bm25_index.get_vectorstore")
def test_invalidate_defect_kb는_noop(mock_get_vectorstore):
    mock_get_vectorstore.return_value = _mock_vectorstore(
        documents=["관리주체는 안전점검을 실시하여야 한다."],
        metadatas=[{"doc_id": "1", "chunk_index": 0}],
        ids=["1_0"],
    )

    first = bm25_index.get_snapshot(COLLECTION_REGULATIONS)
    bm25_index.invalidate(COLLECTION_DEFECT_KB)
    second = bm25_index.get_snapshot(COLLECTION_REGULATIONS)

    # regulations 캐시는 그대로 유지되어야 한다(defect_kb invalidate는 no-op).
    mock_get_vectorstore.assert_called_once()
    assert first is second


@patch("ai.core.bm25_index.get_vectorstore")
def test_get_snapshot_동시호출시_build은한번만실행(mock_get_vectorstore):
    mock_get_vectorstore.return_value = _mock_vectorstore(
        documents=["관리주체는 안전점검을 실시하여야 한다."],
        metadatas=[{"doc_id": "1", "chunk_index": 0}],
        ids=["1_0"],
    )

    results = []
    barrier = threading.Barrier(5)

    def worker():
        barrier.wait()
        results.append(bm25_index.get_snapshot(COLLECTION_REGULATIONS))

    threads = [threading.Thread(target=worker) for _ in range(5)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert mock_get_vectorstore.call_count == 1
    assert all(r is results[0] for r in results)
