"""시맨틱 캐시 보존 정책 — TTL·용량 상한·코퍼스 변경 시 무효화 (#1594 1번/4번 항목).

`ai/core/semantic_cache.py`가 단일 정책 지점이므로 여기서 함께 검증한다:

- 설정값을 **런타임에** 읽는가 / 잘못된 값이 임포트·요청을 죽이지 않고 기본값으로 폴백하는가(4번)
- TTL 경과분이 실제로 삭제되는가, 용량 상한 초과분이 오래된 순으로 축출되는가(1번)
- 문서 삭제·(재)임베딩이 시맨틱 캐시를 무효화하는가 — `regulations`에서만(1번)

Chroma 조작은 실제 PersistentClient(임시 디렉터리)로 검증한다. mock 호출 인자만 보면 where 절
문법이 틀렸거나 chromadb가 빈 리스트 삭제를 거절하는 것 같은 실제 실패를 못 잡는다
(test_rag_chat_chain.py의 #1584 회사 스코프 테스트가 같은 이유로 실제 Chroma를 쓴다).
"""
import importlib
from unittest.mock import patch

import chromadb
import pytest
from chromadb.api.shared_system_client import SharedSystemClient

from ai.core import rag_ingest, semantic_cache
from ai.core.llm_client import CACHE_TTL_SECONDS
from ai.core.semantic_cache import (
    CREATED_AT_FIELD,
    DEFAULT_MAX_ENTRIES,
    DEFAULT_SEMANTIC_CACHE_THRESHOLD,
    DEFAULT_TTL_SECONDS,
    MAX_MAX_ENTRIES,
    MAX_TTL_SECONDS,
    enforce_retention,
    fresh_entry_filter,
    invalidate_on_corpus_change,
    semantic_cache_max_entries,
    semantic_cache_threshold,
    semantic_cache_ttl_seconds,
)
from ai.core.vectorstore import COLLECTION_DEFECT_KB, COLLECTION_REGULATIONS


# ---------------------------------------------------------------------------
# 설정값 로딩 (#1594 4번) — 런타임 읽기 + 잘못된 값 폴백
# ---------------------------------------------------------------------------


def test_defaults_are_used_when_env_is_absent(monkeypatch):
    for name in (
        "SEMANTIC_CACHE_THRESHOLD",
        "SEMANTIC_CACHE_TTL_SECONDS",
        "SEMANTIC_CACHE_MAX_ENTRIES",
    ):
        monkeypatch.delenv(name, raising=False)

    assert semantic_cache_threshold() == DEFAULT_SEMANTIC_CACHE_THRESHOLD
    assert semantic_cache_ttl_seconds() == DEFAULT_TTL_SECONDS
    assert semantic_cache_max_entries() == DEFAULT_MAX_ENTRIES


def test_env_values_are_read_at_call_time_not_import_time(monkeypatch):
    """모듈은 이미 임포트된 상태에서 env를 바꿔도 그 값이 즉시 반영돼야 한다.

    예전 구현은 `rag_chat_chain` 모듈 최상단에서 `float(os.getenv(...))`를 읽어, 값이 첫 임포트
    시점에 고정됐다(`vectorstore.py` `_client()` 주석이 못박은 레포 컨벤션 위반). 그 상태에서는
    이 테스트가 실패한다 — 임포트 순서에 따라 통과/실패가 갈리는 설정값이 되기 때문이다.
    """
    monkeypatch.setenv("SEMANTIC_CACHE_THRESHOLD", "0.5")
    monkeypatch.setenv("SEMANTIC_CACHE_TTL_SECONDS", "60")
    monkeypatch.setenv("SEMANTIC_CACHE_MAX_ENTRIES", "7")

    assert semantic_cache_threshold() == 0.5
    assert semantic_cache_ttl_seconds() == 60
    assert semantic_cache_max_entries() == 7


@pytest.mark.parametrize(
    "raw",
    ["", "   ", "0.95x", "abc", "0", "-0.3", "1.5", "None"],
)
def test_invalid_threshold_falls_back_to_default_without_raising(monkeypatch, raw):
    """잘못된 값이 예외가 아니라 기본값 폴백이어야 한다 — 캐시 임계값 오타 하나로 앱이 죽으면 안 된다."""
    monkeypatch.setenv("SEMANTIC_CACHE_THRESHOLD", raw)
    assert semantic_cache_threshold() == DEFAULT_SEMANTIC_CACHE_THRESHOLD


@pytest.mark.parametrize("raw", ["", "abc", "0", "-100", "3.7"])
def test_invalid_ttl_and_capacity_fall_back_to_defaults(monkeypatch, raw):
    monkeypatch.setenv("SEMANTIC_CACHE_TTL_SECONDS", raw)
    monkeypatch.setenv("SEMANTIC_CACHE_MAX_ENTRIES", raw)
    assert semantic_cache_ttl_seconds() == DEFAULT_TTL_SECONDS
    assert semantic_cache_max_entries() == DEFAULT_MAX_ENTRIES


def test_ttl_and_capacity_have_upper_bounds(monkeypatch):
    """상한이 없으면 `86400`에 0을 하나 더 붙인 오타가 **경고 없이** 백스톱을 10배로 무력화한다
    (threshold를 maximum=1.0으로 막은 것과 같은 원칙 — 코드리뷰 P3)."""
    monkeypatch.setenv("SEMANTIC_CACHE_TTL_SECONDS", str(MAX_TTL_SECONDS + 1))
    monkeypatch.setenv("SEMANTIC_CACHE_MAX_ENTRIES", str(MAX_MAX_ENTRIES + 1))
    assert semantic_cache_ttl_seconds() == DEFAULT_TTL_SECONDS
    assert semantic_cache_max_entries() == DEFAULT_MAX_ENTRIES

    # 경계값 자체는 허용된다(상한 = 허용 최댓값).
    monkeypatch.setenv("SEMANTIC_CACHE_TTL_SECONDS", str(MAX_TTL_SECONDS))
    monkeypatch.setenv("SEMANTIC_CACHE_MAX_ENTRIES", str(MAX_MAX_ENTRIES))
    assert semantic_cache_ttl_seconds() == MAX_TTL_SECONDS
    assert semantic_cache_max_entries() == MAX_MAX_ENTRIES


def test_default_ttl_is_24h_matching_exact_cache_window():
    """설계 판단 1 — TTL 기본값은 exact(Redis) 캐시와 같은 24시간이다.

    7일에서 낮춘 근거: ①무효화 훅에 write-after-purge 구조적 창이 있고 ②프롬프트·모델 변경에는
    아직 훅이 없어, TTL은 "훅 우회 변경의 백스톱"만이 아니라 그 누락분을 덮는 상한이다.
    값이 조용히 늘어나면 그 노출 창도 함께 늘어나므로 상수로 고정한다.
    """
    assert DEFAULT_TTL_SECONDS == 60 * 60 * 24
    assert DEFAULT_TTL_SECONDS == CACHE_TTL_SECONDS


def test_invalid_env_does_not_break_module_import(monkeypatch):
    """#1594 4번의 핵심 — 잘못된 값이 **임포트 시점 ValueError로 앱 전체를 죽이면** 안 된다.

    체인 모듈까지 함께 리로드해, 어떤 모듈도 임포트 시점에 이 env를 파싱하지 않음을 고정한다.
    """
    monkeypatch.setenv("SEMANTIC_CACHE_THRESHOLD", "완전히-숫자가-아님")
    monkeypatch.setenv("SEMANTIC_CACHE_TTL_SECONDS", "역시-아님")
    monkeypatch.setenv("SEMANTIC_CACHE_MAX_ENTRIES", "아님")

    importlib.reload(semantic_cache)
    importlib.reload(importlib.import_module("ai.chains.rag_chat_chain"))

    assert semantic_cache.semantic_cache_threshold() == DEFAULT_SEMANTIC_CACHE_THRESHOLD


@pytest.fixture(autouse=True)
def _restore_module_state():
    """위 리로드 테스트가 남긴 모듈 객체를 원상복구한다 — 리로드로 새로 만들어진 클래스/그래프
    객체가 다음 테스트의 patch 대상과 어긋나면(같은 이름, 다른 객체) 순서 의존 플레이키가 된다."""
    yield
    importlib.reload(semantic_cache)
    importlib.reload(importlib.import_module("ai.chains.rag_chat_chain"))


# ---------------------------------------------------------------------------
# TTL 필터 (#1594 1번)
# ---------------------------------------------------------------------------


def test_fresh_entry_filter_enforces_both_company_scope_and_ttl(monkeypatch):
    monkeypatch.setenv("SEMANTIC_CACHE_TTL_SECONDS", "100")
    with patch.object(semantic_cache, "now_epoch_seconds", return_value=1_000_000):
        assert fresh_entry_filter(7) == {
            "$and": [
                {"company_id": 7},
                {CREATED_AT_FIELD: {"$gte": 999_900}},
            ]
        }


# ---------------------------------------------------------------------------
# 실제 Chroma 기반 — enforce_retention / invalidate_on_corpus_change
# ---------------------------------------------------------------------------


@pytest.fixture
def real_cache_collection(tmp_path):
    """임시 디렉터리의 실제 chromadb 컬렉션을 semantic_cache 자리에 꽂는다.

    `semantic_cache._semantic_collection()`이 반환하는 핸들을 그대로 대체하므로, 이 모듈이
    실제 chromadb API(where 연산자·빈 리스트 삭제 거절 등)로 동작하는지 검증할 수 있다.
    teardown에서 chromadb 전역 시스템 레지스트리를 비운다(test_rag_chat_chain.py와 동일 이유).
    """
    client = chromadb.PersistentClient(path=str(tmp_path / "chroma"))
    collection = client.get_or_create_collection(
        "semantic_cache", metadata={"hnsw:space": "cosine"}
    )
    try:
        with patch.object(semantic_cache, "_semantic_collection", return_value=collection):
            yield collection
    finally:
        SharedSystemClient.clear_system_cache()


def _add_entry(collection, entry_id: str, created_at: int, company_id: int = 1):
    collection.add(
        ids=[entry_id],
        embeddings=[[1.0, 0.0]],
        documents=[f"질문 {entry_id}"],
        metadatas=[{"company_id": company_id, CREATED_AT_FIELD: created_at}],
    )


def test_enforce_retention_deletes_expired_entries(monkeypatch, real_cache_collection):
    monkeypatch.setenv("SEMANTIC_CACHE_TTL_SECONDS", "100")
    monkeypatch.setenv("SEMANTIC_CACHE_MAX_ENTRIES", "1000")

    with patch.object(semantic_cache, "now_epoch_seconds", return_value=1_000_000):
        _add_entry(real_cache_collection, "expired", created_at=999_800)  # 200초 전 → 만료
        _add_entry(real_cache_collection, "fresh", created_at=999_950)  # 50초 전 → 유효
        enforce_retention()

    assert real_cache_collection.get(include=[])["ids"] == ["fresh"]


def test_enforce_retention_keeps_everything_when_nothing_expired(
    monkeypatch, real_cache_collection
):
    """TTL이 멀쩡한 항목까지 지우면 캐시가 무의미해진다 — 과잉 삭제 회귀 방지."""
    monkeypatch.setenv("SEMANTIC_CACHE_TTL_SECONDS", "100000")
    monkeypatch.setenv("SEMANTIC_CACHE_MAX_ENTRIES", "1000")

    with patch.object(semantic_cache, "now_epoch_seconds", return_value=1_000_000):
        _add_entry(real_cache_collection, "a", created_at=999_990)
        _add_entry(real_cache_collection, "b", created_at=999_995)
        enforce_retention()

    assert sorted(real_cache_collection.get(include=[])["ids"]) == ["a", "b"]


def test_enforce_retention_evicts_oldest_entries_over_capacity(
    monkeypatch, real_cache_collection
):
    """상한 x 트리거배수를 넘으면 상한 x 목표배수까지 오래된 순으로 줄인다(설계 판단 3)."""
    monkeypatch.setenv("SEMANTIC_CACHE_TTL_SECONDS", "100000")  # 만료 없음 — 상한만 검증
    monkeypatch.setenv("SEMANTIC_CACHE_MAX_ENTRIES", "10")

    with patch.object(semantic_cache, "now_epoch_seconds", return_value=1_000_000):
        # 12건 > 10 * 1.1 = 11 → 트리거. 목표 = int(10 * 0.9) = 9 → 오래된 3건 축출.
        for i in range(12):
            _add_entry(real_cache_collection, f"e{i:02d}", created_at=999_900 + i)
        enforce_retention()

    remaining = sorted(real_cache_collection.get(include=[])["ids"])
    assert remaining == [f"e{i:02d}" for i in range(3, 12)]


def test_enforce_retention_does_not_evict_within_hysteresis_margin(
    monkeypatch, real_cache_collection
):
    """상한을 살짝 넘긴 정도(트리거 배수 이내)로는 축출하지 않는다 — 이 여유가 없으면 상한 도달 후
    **모든** 저장이 전량 조회+정렬+삭제를 사용자 요청 경로에서 떠안는다(코드리뷰 P3)."""
    monkeypatch.setenv("SEMANTIC_CACHE_TTL_SECONDS", "100000")
    monkeypatch.setenv("SEMANTIC_CACHE_MAX_ENTRIES", "10")

    with patch.object(semantic_cache, "now_epoch_seconds", return_value=1_000_000):
        # 11건 <= 10 * 1.1 = 11 → 트리거하지 않는다.
        for i in range(11):
            _add_entry(real_cache_collection, f"e{i:02d}", created_at=999_900 + i)
        enforce_retention()

    assert real_cache_collection.count() == 11


def _add_legacy_entry(collection, entry_id: str, company_id: int | None = 1):
    """#1594 이전 적재분 재현 — `created_at`이 아예 없다(company_id 유무는 세대에 따라 다르다)."""
    metadata = {"company_id": company_id} if company_id is not None else {"answer_json": "{}"}
    collection.add(
        ids=[entry_id],
        embeddings=[[1.0, 0.0]],
        documents=[f"레거시 질문 {entry_id}"],
        metadatas=[metadata],
    )


def test_enforce_retention_deletes_legacy_entries_without_created_at(
    monkeypatch, real_cache_collection
):
    """#1594 코드리뷰 P2 — `created_at` 필드 자체가 없는 항목은 `$lt` where 삭제에 **매칭되지 않아**
    영원히 남는다. 조회에서는 빠지지만(fail-closed) 질문 원문 평문이 디스크에 무기한 잔존하므로
    (harm 3), 용량 분기와 무관하게 별도 경로로 지워야 한다.

    #1584 이전(company_id도 없는) 세대까지 함께 정리되는 것을 확인한다 — 트러블슈팅 문서가 배포
    전제조건으로 남긴 "수동 1회 purge"가 이 경로로 자동화된다.
    """
    monkeypatch.setenv("SEMANTIC_CACHE_TTL_SECONDS", "100000")
    monkeypatch.setenv("SEMANTIC_CACHE_MAX_ENTRIES", "1000")  # 용량 축출은 절대 트리거되지 않는다

    with patch.object(semantic_cache, "now_epoch_seconds", return_value=1_000_000):
        _add_legacy_entry(real_cache_collection, "legacy_1584", company_id=None)  # 1584 이전 세대
        _add_legacy_entry(real_cache_collection, "legacy_1594", company_id=3)  # 1584~1594 세대
        _add_entry(real_cache_collection, "fresh", created_at=999_990)
        enforce_retention()

    assert real_cache_collection.get(include=[])["ids"] == ["fresh"]


def test_legacy_sweep_is_noop_when_all_entries_are_stamped(monkeypatch, real_cache_collection):
    """정상 항목만 있으면 레거시 스윕이 아무것도 지우지 않는다 — 한 번 정리된 뒤에는 사실상 no-op."""
    monkeypatch.setenv("SEMANTIC_CACHE_TTL_SECONDS", "100000")
    monkeypatch.setenv("SEMANTIC_CACHE_MAX_ENTRIES", "1000")

    with patch.object(semantic_cache, "now_epoch_seconds", return_value=1_000_000):
        _add_entry(real_cache_collection, "a", created_at=999_990)
        _add_entry(real_cache_collection, "b", created_at=999_995)
        enforce_retention()

    assert sorted(real_cache_collection.get(include=[])["ids"]) == ["a", "b"]


def test_enforce_retention_survives_chroma_failure(monkeypatch):
    """보존 정책 유지 실패가 사용자 요청을 죽이면 안 된다(이미 답변은 생성돼 있다)."""
    with patch.object(
        semantic_cache, "_semantic_collection", side_effect=RuntimeError("chroma 장애")
    ):
        enforce_retention()  # 예외가 전파되면 테스트 실패


def test_invalidate_on_corpus_change_clears_all_entries(real_cache_collection):
    _add_entry(real_cache_collection, "a", created_at=1)
    _add_entry(real_cache_collection, "b", created_at=2)

    assert invalidate_on_corpus_change(COLLECTION_REGULATIONS) == 2
    assert real_cache_collection.count() == 0


def test_invalidate_on_corpus_change_on_empty_collection_is_safe(real_cache_collection):
    """chromadb는 빈 id 리스트 삭제를 ValueError로 거절한다(실측) — 빈 캐시 purge가 터지면 안 된다."""
    assert invalidate_on_corpus_change(COLLECTION_REGULATIONS) == 0
    assert real_cache_collection.count() == 0


def test_invalidate_on_corpus_change_is_noop_for_non_regulations(real_cache_collection):
    """defect_kb는 `hybrid_search` 대상이 아니라 RAG 답변에 영향이 없다 — bm25_index.invalidate와
    동일한 no-op 규약(무관한 변경으로 캐시를 통째로 날리지 않는다)."""
    _add_entry(real_cache_collection, "a", created_at=1)

    assert invalidate_on_corpus_change(COLLECTION_DEFECT_KB) == 0
    assert real_cache_collection.count() == 1


# ---------------------------------------------------------------------------
# 코퍼스 변경 → 무효화 연동 (#1594 1번 완료기준: "문서 삭제/재임베딩 후 관련 캐시가 무효화됨")
# ---------------------------------------------------------------------------


def test_delete_document_purges_semantic_cache():
    """삭제된 문서를 인용한 citation이 캐시에서 계속 나가지 않도록, 문서 삭제가 캐시를 무효화한다."""
    with patch.object(rag_ingest, "get_vectorstore"), patch.object(
        rag_ingest, "invalidate_on_corpus_change"
    ) as mock_purge, patch.object(rag_ingest.bm25_index, "invalidate"):
        rag_ingest.delete_document("42", COLLECTION_REGULATIONS)

    mock_purge.assert_called_once_with(COLLECTION_REGULATIONS)


def test_delete_stale_chunks_purges_semantic_cache():
    with patch.object(rag_ingest, "get_vectorstore"), patch.object(
        rag_ingest, "invalidate_on_corpus_change"
    ) as mock_purge, patch.object(rag_ingest.bm25_index, "invalidate"):
        rag_ingest.delete_stale_chunks("42", COLLECTION_REGULATIONS, 3)

    mock_purge.assert_called_once_with(COLLECTION_REGULATIONS)


def test_ingest_document_purges_semantic_cache():
    """법규 개정 재임베딩 후에도 구 답변이 영구 서빙되던 것이 #1594 1번의 핵심 피해다."""
    with patch.object(rag_ingest, "get_vectorstore"), patch.object(
        rag_ingest, "invalidate_on_corpus_change"
    ) as mock_purge, patch.object(rag_ingest.bm25_index, "invalidate"):
        chunk_count = rag_ingest.ingest_document(
            doc_id="42",
            title="시설물의 안전 및 유지관리에 관한 특별법",
            doc_type="LAW",
            target_collection=COLLECTION_REGULATIONS,
            text="제1조(목적) 이 법은 시설물의 안전을 확보함을 목적으로 한다.",
        )

    assert chunk_count > 0
    mock_purge.assert_called_once_with(COLLECTION_REGULATIONS)


def test_ingest_into_defect_kb_does_not_purge_regulations_cache():
    """하자 지식 적재는 RAG 챗봇 검색 대상(regulations)이 아니므로 캐시를 날리지 않는다."""
    # 무효화 함수를 mock하지 않고 **실제로 태운다** — no-op 규약이 깨지면 캐시 컬렉션에 접근하게
    # 되므로, 접근 지점(`_semantic_collection`) 미호출로 no-op을 증명한다.
    with patch.object(rag_ingest, "get_vectorstore"), patch.object(
        rag_ingest.bm25_index, "invalidate"
    ), patch.object(semantic_cache, "_semantic_collection") as mock_collection:
        rag_ingest.ingest_document(
            doc_id="7",
            title="하자 지식",
            doc_type="KB",
            target_collection=COLLECTION_DEFECT_KB,
            text="누수는 방수층 손상에서 비롯되는 경우가 많다." * 10,
        )

    mock_collection.assert_not_called()
