"""ai.core.hybrid_search 검증 — 벡터+BM25 RRF 결합 하이브리드 검색(#1410).

get_vectorstore()/bm25_index.get_snapshot()을 모킹해 실제 Chroma/HF 호출 없이,
(1) _reciprocal_rank_fusion 순수함수의 점수 계산·정렬·합집합 동작과
(2) hybrid_search()의 top-k 호출/결합 동작을 검증한다.
"""
from unittest.mock import MagicMock, patch

from langchain_core.documents import Document

from ai.core.hybrid_search import (
    DEFAULT_BM25_TOP_K,
    DEFAULT_RRF_K,
    DEFAULT_TOP_K,
    DEFAULT_VECTOR_TOP_K,
    _reciprocal_rank_fusion,
    hybrid_search,
)


def _doc(doc_id, chunk_index, content="본문"):
    return Document(page_content=content, metadata={"doc_id": doc_id, "chunk_index": chunk_index})


# ---------------------------------------------------------------------------
# _reciprocal_rank_fusion — 순수함수
# ---------------------------------------------------------------------------


def test_rrf_두랭킹모두에있는문서가점수를합산한다():
    a = _doc("1", 0)
    b = _doc("1", 1)

    vector_ranked = [a, b]  # a: rank0, b: rank1
    bm25_ranked = [b, a]  # b: rank0, a: rank1

    rrf_k = 60
    result = _reciprocal_rank_fusion([vector_ranked, bm25_ranked], rrf_k=rrf_k, top_k=10)

    # a: 1/(60+0) + 1/(60+1) , b: 1/(60+1) + 1/(60+0) — 동점이므로 vector 랭킹(첫 리스트) 우선.
    expected_score_a = 1 / 60 + 1 / 61
    expected_score_b = 1 / 61 + 1 / 60
    assert expected_score_a == expected_score_b
    assert [d.metadata["doc_id"] for d in result] == ["1", "1"]
    # 동점 tie-break: 벡터 리스트에서 먼저 나온(a) 문서가 앞선다.
    assert result[0] is a
    assert result[1] is b


def test_rrf_한쪽에만있는문서도합집합으로포함():
    only_vector = _doc("1", 0)
    only_bm25 = _doc("2", 0)

    result = _reciprocal_rank_fusion([[only_vector], [only_bm25]], rrf_k=60, top_k=10)

    ids = {d.metadata["doc_id"] for d in result}
    assert ids == {"1", "2"}
    assert len(result) == 2


def test_rrf_점수내림차순정렬():
    top_hit = _doc("1", 0)  # 양쪽 리스트 모두에서 rank 0
    mid_hit = _doc("2", 0)  # 벡터에서만 rank 1
    low_hit = _doc("3", 0)  # bm25에서만 rank 5

    vector_ranked = [top_hit, mid_hit]
    bm25_ranked = [top_hit] + [_doc(str(i), 0) for i in range(10, 15)] + [low_hit]

    result = _reciprocal_rank_fusion([vector_ranked, bm25_ranked], rrf_k=60, top_k=3)

    assert result[0].metadata["doc_id"] == "1"  # 양쪽 다 상위 랭크라 최고점


def test_rrf_top_k만큼만반환():
    docs = [_doc(str(i), 0) for i in range(20)]
    result = _reciprocal_rank_fusion([docs, []], rrf_k=60, top_k=5)
    assert len(result) == 5


def test_rrf_빈리스트는빈결과():
    assert _reciprocal_rank_fusion([[], []], rrf_k=60, top_k=10) == []


# ---------------------------------------------------------------------------
# hybrid_search — 벡터/BM25 모킹 결합
# ---------------------------------------------------------------------------


@patch("ai.core.hybrid_search.bm25_index")
@patch("ai.core.hybrid_search.get_vectorstore")
def test_hybrid_search_벡터와bm25를top_k로호출(mock_get_vectorstore, mock_bm25_index):
    mock_vs = MagicMock()
    mock_vs.similarity_search.return_value = [_doc("1", 0)]
    mock_get_vectorstore.return_value = mock_vs

    mock_snapshot = MagicMock()
    mock_snapshot.documents = ["본문1", "본문2"]
    mock_snapshot.metadatas = [{"doc_id": "2", "chunk_index": 0}, {"doc_id": "3", "chunk_index": 0}]
    mock_snapshot.bm25.get_scores.return_value = [0.5, 0.9]
    mock_bm25_index.get_snapshot.return_value = mock_snapshot
    mock_bm25_index._tokenize.return_value = ["질의", "토큰"]

    result = hybrid_search("질의", k=2, vector_k=10, bm25_k=10, rrf_k=60)

    mock_vs.similarity_search.assert_called_once_with("질의", k=10)
    mock_bm25_index.get_snapshot.assert_called_once()
    assert len(result) <= 2
    ids = [d.metadata["doc_id"] for d in result]
    assert set(ids).issubset({"1", "2", "3"})


@patch("ai.core.hybrid_search.bm25_index")
@patch("ai.core.hybrid_search.get_vectorstore")
def test_hybrid_search_기본값은os_getenv로결정(mock_get_vectorstore, mock_bm25_index):
    mock_vs = MagicMock()
    mock_vs.similarity_search.return_value = []
    mock_get_vectorstore.return_value = mock_vs

    mock_snapshot = MagicMock()
    mock_snapshot.documents = []
    mock_snapshot.metadatas = []
    mock_bm25_index.get_snapshot.return_value = mock_snapshot

    hybrid_search("질의")

    mock_vs.similarity_search.assert_called_once_with("질의", k=DEFAULT_VECTOR_TOP_K)


@patch("ai.core.hybrid_search.bm25_index")
@patch("ai.core.hybrid_search.get_vectorstore")
def test_hybrid_search_둘다0건이면빈리스트(mock_get_vectorstore, mock_bm25_index):
    mock_vs = MagicMock()
    mock_vs.similarity_search.return_value = []
    mock_get_vectorstore.return_value = mock_vs

    mock_snapshot = MagicMock()
    mock_snapshot.documents = []
    mock_snapshot.metadatas = []
    mock_bm25_index.get_snapshot.return_value = mock_snapshot

    result = hybrid_search("질의", k=4, vector_k=10, bm25_k=10, rrf_k=60)

    assert result == []


def test_defaults_존재():
    assert DEFAULT_TOP_K == 4
    assert DEFAULT_VECTOR_TOP_K == 10
    assert DEFAULT_BM25_TOP_K == 10
    # 60(RRF 관용값)이 아니라 1 — #1410 실측(law.go.kr 공식 법규 PDF 7건, 질의 27개)에서
    # rrf_k=60은 순위 차이가 거의 반영되지 않아 vector-only보다 성능이 낮았고, rrf_k=1로
    # 낮추자 recall@10/MRR/nDCG가 전부 개선됨을 확인했다(hybrid_search.py 모듈 상수 주석 참고).
    assert DEFAULT_RRF_K == 1
