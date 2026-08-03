"""표준 정보검색 지표 — 벡터-only vs 하이브리드 검색 정량 비교(#1410)용 순수함수.

모두 "retrieved_ids"(검색 결과 랭킹 순 문서 id 리스트)와 "relevant_ids"(정답 id 집합/리스트)를
입력으로 받는다. id는 Chroma chunk id(예: "42_0") 문자열이라고 가정한다.
"""
from __future__ import annotations

import math


def recall_at_k(retrieved_ids: list[str], relevant_ids: list[str], k: int) -> float:
    """상위 k개 검색 결과 중 정답 문서를 얼마나 회수했는지(0.0~1.0).

    relevant_ids가 비어있으면 정의상 0.0(분모 없음 — 호출부에서 그런 질의는 걸러내는 것을 권장)."""
    if not relevant_ids:
        return 0.0
    top_k = set(retrieved_ids[:k])
    relevant = set(relevant_ids)
    hit_count = len(top_k & relevant)
    return hit_count / len(relevant)


def mrr(retrieved_ids: list[str], relevant_ids: list[str]) -> float:
    """Mean Reciprocal Rank(단일 질의 기준 Reciprocal Rank) — 첫 정답 문서가 나온 순위(1-base)의
    역수. 정답을 하나도 못 찾으면 0.0."""
    relevant = set(relevant_ids)
    for rank, doc_id in enumerate(retrieved_ids, start=1):
        if doc_id in relevant:
            return 1.0 / rank
    return 0.0


def _dcg(gains: list[float]) -> float:
    return sum(gain / math.log2(i + 2) for i, gain in enumerate(gains))  # rank는 0-base, log2(rank+2)


def ndcg_at_k(retrieved_ids: list[str], relevant_ids: list[str], k: int) -> float:
    """nDCG@k — 이진 관련성(정답 집합에 속하면 gain=1, 아니면 0) 기준.

    ideal DCG는 relevant_ids 개수(최대 k개)만큼 gain=1이 앞쪽에 오는 경우."""
    if not relevant_ids:
        return 0.0
    relevant = set(relevant_ids)
    gains = [1.0 if doc_id in relevant else 0.0 for doc_id in retrieved_ids[:k]]
    dcg = _dcg(gains)

    ideal_gains = [1.0] * min(len(relevant), k)
    idcg = _dcg(ideal_gains)
    if idcg == 0.0:
        return 0.0
    return dcg / idcg
