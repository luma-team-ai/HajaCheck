"""ai/eval/metrics.py 순수함수 검증 — 표준 정보검색 지표(#1410 평가 하네스). run_eval.py 본체는
실제 Chroma/HF 호출을 수반해 pytest 대상이 아니지만, 지표 계산 로직은 순수함수라 유닛테스트로
커버한다."""
from ai.eval.metrics import mrr, ndcg_at_k, recall_at_k


def test_recall_at_k_전부히트():
    assert recall_at_k(["1", "2", "3"], ["1", "2"], k=3) == 1.0


def test_recall_at_k_일부히트():
    assert recall_at_k(["1", "9", "9"], ["1", "2"], k=3) == 0.5


def test_recall_at_k_k보다뒤에있으면미포함():
    assert recall_at_k(["9", "9", "1"], ["1"], k=2) == 0.0


def test_recall_at_k_relevant가비어있으면0():
    assert recall_at_k(["1", "2"], [], k=2) == 0.0


def test_mrr_첫번째가정답이면1():
    assert mrr(["1", "2"], ["1"]) == 1.0


def test_mrr_세번째가정답이면1_3():
    assert mrr(["9", "9", "1"], ["1"]) == 1 / 3


def test_mrr_정답없으면0():
    assert mrr(["9", "9"], ["1"]) == 0.0


def test_ndcg_at_k_이상적순서면1():
    assert ndcg_at_k(["1", "2"], ["1", "2"], k=2) == 1.0


def test_ndcg_at_k_역순이면1보다작다():
    score = ndcg_at_k(["2", "1"], ["1"], k=2)
    assert 0.0 < score < 1.0


def test_ndcg_at_k_정답없으면0():
    assert ndcg_at_k(["9", "9"], ["1"], k=2) == 0.0


def test_ndcg_at_k_relevant가비어있으면0():
    assert ndcg_at_k(["1", "2"], [], k=2) == 0.0
