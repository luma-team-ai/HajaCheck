"""벡터-only 검색 vs 하이브리드(벡터+BM25 RRF) 검색 정량 비교 — #1410 포트폴리오용 평가 하네스.

`python -m ai.eval.run_eval`로 실행한다. pytest 대상이 아니고 CI 자동실행 대상도 아니다(로컬/수동
전용 — 실제 HuggingFace 임베딩 모델 로드와 Chroma 조회를 수행하므로 느리고, 프로젝트 CHROMA_PERSIST_DIR
설정에 실제로 적재된 regulations 데이터가 있어야 의미 있는 결과가 나온다).

⚠️ ai/eval/eval_dataset.json은 이 레포 실행 환경에 별도 Chroma 데이터 볼륨(chroma_data/)이 없어
합성(synthetic) 소규모 데이터셋이다 — 실제 regulations 컬렉션에 해당 chunk id들이 존재하지 않으면
Recall/MRR/nDCG가 전부 0에 가깝게 나오는 것이 정상이다(스크립트 자체가 에러 없이 끝까지 도는지가
1차 목표). 실 데이터가 적재된 환경에서는 eval_dataset.json을 그 데이터 기준으로 다시 작성해야 한다.
"""
from __future__ import annotations

import csv
import json
from datetime import datetime, timezone
from pathlib import Path

import matplotlib

matplotlib.use("Agg")  # 헤드리스 환경에서 GUI 백엔드 없이 파일로만 저장
import matplotlib.pyplot as plt

from ai.core.hybrid_search import hybrid_search
from ai.core.vectorstore import COLLECTION_REGULATIONS, get_vectorstore
from ai.eval.metrics import mrr, ndcg_at_k, recall_at_k

DATASET_PATH = Path(__file__).resolve().parent / "eval_dataset.json"
RESULTS_DIR = Path(__file__).resolve().parent.parent.parent / "eval_results"

RECALL_KS = (4, 10)
NDCG_K = 10


def _chunk_ref(doc) -> str:
    metadata = doc.metadata
    return f"{metadata.get('doc_id')}_{metadata.get('chunk_index')}"


def _vector_only_search(query: str, k: int) -> list[str]:
    docs = get_vectorstore(COLLECTION_REGULATIONS).similarity_search(query, k=k)
    return [_chunk_ref(d) for d in docs]


def _hybrid_search_ids(query: str, k: int) -> list[str]:
    docs = hybrid_search(query, k=k)
    return [_chunk_ref(d) for d in docs]


def _evaluate_path(name: str, search_fn, dataset: list[dict]) -> dict:
    max_k = max(RECALL_KS + (NDCG_K,))
    recall_sums = {k: 0.0 for k in RECALL_KS}
    mrr_sum = 0.0
    ndcg_sum = 0.0
    n = len(dataset)

    for item in dataset:
        query = item["query"]
        relevant = item["relevant_chunk_ids"]
        try:
            retrieved = search_fn(query, max_k)
        except Exception as exc:  # noqa: BLE001 — 평가 스크립트는 한 질의 실패로 전체가 죽으면 안 됨
            print(f"  [경고] '{name}' 검색 실패(query={query!r}): {exc}")
            retrieved = []

        for k in RECALL_KS:
            recall_sums[k] += recall_at_k(retrieved, relevant, k)
        mrr_sum += mrr(retrieved, relevant)
        ndcg_sum += ndcg_at_k(retrieved, relevant, NDCG_K)

    return {
        "name": name,
        **{f"recall@{k}": recall_sums[k] / n for k in RECALL_KS},
        "mrr": mrr_sum / n,
        f"ndcg@{NDCG_K}": ndcg_sum / n,
    }


def _print_table(rows: list[dict]) -> None:
    headers = list(rows[0].keys())
    widths = [max(len(str(r[h])) for r in rows + [dict(zip(headers, headers))]) for h in headers]
    header_line = " | ".join(h.ljust(w) for h, w in zip(headers, widths))
    print(header_line)
    print("-" * len(header_line))
    for row in rows:
        print(" | ".join(str(row[h]).ljust(w) for h, w in zip(headers, widths)))


def _save_csv(rows: list[dict], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)


def _save_chart(rows: list[dict], path: Path) -> None:
    metric_keys = [k for k in rows[0].keys() if k != "name"]
    names = [r["name"] for r in rows]

    x = range(len(metric_keys))
    width = 0.8 / len(rows)

    fig, ax = plt.subplots(figsize=(8, 5))
    for i, row in enumerate(rows):
        values = [row[m] for m in metric_keys]
        positions = [xi + i * width for xi in x]
        ax.bar(positions, values, width=width, label=row["name"])

    ax.set_xticks([xi + width * (len(rows) - 1) / 2 for xi in x])
    ax.set_xticklabels(metric_keys)
    ax.set_ylabel("score")
    ax.set_title("벡터-only vs 하이브리드 검색 — 정보검색 지표 비교 (#1410)")
    ax.legend()
    fig.tight_layout()
    path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(path)
    plt.close(fig)


def main() -> None:
    dataset = json.loads(DATASET_PATH.read_text(encoding="utf-8"))
    print(f"평가 질의 {len(dataset)}건 로드 — {DATASET_PATH}")

    print("\n[벡터-only 검색 실행 중...]")
    vector_result = _evaluate_path("vector-only", _vector_only_search, dataset)

    print("[하이브리드 검색(BM25+RRF) 실행 중...]")
    hybrid_result = _evaluate_path("hybrid", _hybrid_search_ids, dataset)

    rows = [vector_result, hybrid_result]
    print("\n=== 결과 비교 ===")
    _print_table(rows)

    timestamp = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")
    csv_path = RESULTS_DIR / f"{timestamp}.csv"
    png_path = RESULTS_DIR / f"{timestamp}.png"

    _save_csv(rows, csv_path)
    print(f"\nCSV 저장: {csv_path}")

    try:
        _save_chart(rows, png_path)
        print(f"차트 저장: {png_path}")
    except Exception as exc:  # noqa: BLE001 — 차트 저장 실패해도 CSV 결과는 이미 남아야 함
        print(f"[경고] 차트 저장 실패: {exc}")


if __name__ == "__main__":
    main()
