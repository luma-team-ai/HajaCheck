"""rag_ingest(ingest_document/delete_document) 및 /ai/rag-documents/embed 엔드포인트 검증 — #22/HAJA-35.

실제 HuggingFace 임베딩 모델/디스크 Chroma를 쓰지 않고 get_vectorstore()만 모킹해 add_texts() 호출
인자(메타데이터 필드 정확성)를 검증한다(handoff §AI-server 테스트 지시 그대로).
"""
from unittest.mock import MagicMock, patch

from fastapi.testclient import TestClient

from ai.core.rag_ingest import delete_document, ingest_document
from ai.core.vectorstore import COLLECTION_DEFECT_KB, COLLECTION_REGULATIONS
from main import app

client = TestClient(app)

FLAT_LAW_SAMPLE = (
    "제1조(목적) 이 지침은 시설물의 안전점검 및 정밀안전진단의 실시 등에 필요한 사항을 규정한다. "
    "제2조(정의) 이 지침에서 사용하는 용어의 뜻은 다음과 같다. "
    "①안전점검이란 경험과 기술을 갖춘 자가 육안이나 점검기구로 조사하는 것을 말한다."
)


@patch("ai.core.rag_ingest.get_vectorstore")
def test_ingest_document_regulations_청킹결과와메타데이터를add_texts에전달(mock_get_vectorstore):
    mock_vs = MagicMock()
    mock_get_vectorstore.return_value = mock_vs

    chunk_count = ingest_document(
        doc_id="42",
        title="시설물의 안전관리에 관한 특별법 시행령",
        doc_type="LAW",
        target_collection=COLLECTION_REGULATIONS,
        text=FLAT_LAW_SAMPLE,
        effective_date="2026-01-01",
        publisher="국토교통부",
    )

    mock_get_vectorstore.assert_called_once_with(COLLECTION_REGULATIONS)
    mock_vs.add_texts.assert_called_once()
    call_kwargs = mock_vs.add_texts.call_args.kwargs
    texts = call_kwargs["texts"]
    metadatas = call_kwargs["metadatas"]
    ids = call_kwargs["ids"]

    assert chunk_count == len(texts) == len(metadatas) == len(ids)
    assert chunk_count >= 1
    assert ids[0] == "42_0"

    first = metadatas[0]
    assert first["doc_id"] == "42"
    assert first["source"] == "시설물의 안전관리에 관한 특별법 시행령"
    assert first["doc_type"] == "LAW"
    assert first["chunk_index"] == 0
    assert first["effective_date"] == "2026-01-01"
    assert first["publisher"] == "국토교통부"
    assert first["embedding_model"] == "BAAI/bge-m3"
    assert "embedded_at" in first
    assert "chunk_hash" in first
    # 첫 청크는 제1조로 시작하므로 article 메타데이터가 채워져야 한다.
    assert first["article"] == "제1조"

    # defect_kb 전용 필드(authored_at/verification_status)는 regulations 청크에 없어야 한다.
    assert "authored_at" not in first
    assert "verification_status" not in first


@patch("ai.core.rag_ingest.get_vectorstore")
def test_ingest_document_결측필드는키자체를생략(mock_get_vectorstore):
    """effective_date/publisher를 안 넘기면 메타데이터에 그 키 자체가 없어야 한다
    (rag_chroma_schema.md §3 "결측값: 키 자체를 생성하지 않는다")."""
    mock_vs = MagicMock()
    mock_get_vectorstore.return_value = mock_vs

    ingest_document(
        doc_id="7",
        title="문서",
        doc_type="LAW",
        target_collection=COLLECTION_REGULATIONS,
        text=FLAT_LAW_SAMPLE,
    )

    metadatas = mock_vs.add_texts.call_args.kwargs["metadatas"]
    for metadata in metadatas:
        assert "effective_date" not in metadata
        assert "publisher" not in metadata


@patch("ai.core.rag_ingest.get_vectorstore")
def test_ingest_document_defect_kb_authored_at과verification_status를포함(mock_get_vectorstore):
    mock_vs = MagicMock()
    mock_get_vectorstore.return_value = mock_vs

    chunk_count = ingest_document(
        doc_id="100",
        title="하자 유형별 보수 지침",
        doc_type="GUIDELINE",
        target_collection=COLLECTION_DEFECT_KB,
        text="균열은 콘크리트 표면에 발생하는 선형 결함이다. " * 20,
        authored_at="2026-07-01",
        verification_status="VERIFIED",
    )

    mock_get_vectorstore.assert_called_once_with(COLLECTION_DEFECT_KB)
    assert chunk_count > 0
    metadatas = mock_vs.add_texts.call_args.kwargs["metadatas"]
    first = metadatas[0]
    assert first["authored_at"] == "2026-07-01"
    assert first["verification_status"] == "VERIFIED"
    # regulations 전용 필드(article/clause/effective_date/publisher)는 defect_kb 청크에 없어야 한다.
    assert "article" not in first
    assert "effective_date" not in first
    assert "publisher" not in first


def test_ingest_document_알수없는컬렉션은ValueError():
    try:
        ingest_document(
            doc_id="1", title="문서", doc_type="LAW",
            target_collection="not_a_real_collection", text="본문",
        )
        assert False, "ValueError가 발생해야 한다"
    except ValueError as e:
        assert "unknown target_collection" in str(e)


@patch("ai.core.rag_ingest.get_vectorstore")
def test_delete_document_doc_id로where삭제(mock_get_vectorstore):
    mock_vs = MagicMock()
    mock_get_vectorstore.return_value = mock_vs

    delete_document("42", COLLECTION_REGULATIONS)

    mock_get_vectorstore.assert_called_once_with(COLLECTION_REGULATIONS)
    mock_vs.delete.assert_called_once_with(where={"doc_id": "42"})


# ── /ai/rag-documents/embed 엔드포인트 ──

@patch("routers.ai_router.delete_document")
@patch("routers.ai_router.ingest_document")
def test_embed_endpoint_성공_chunk_count반환(mock_ingest, mock_delete):
    mock_ingest.return_value = 5

    res = client.post(
        "/ai/rag-documents/embed",
        json={
            "doc_id": "42",
            "title": "시설물 안전법",
            "doc_type": "LAW",
            "target_collection": "regulations",
            "text": FLAT_LAW_SAMPLE,
            "effective_date": "2026-01-01",
        },
    )

    assert res.status_code == 200
    body = res.json()
    assert body["success"] is True
    assert body["data"]["chunk_count"] == 5
    mock_delete.assert_called_once_with("42", "regulations")
    mock_ingest.assert_called_once()


@patch("routers.ai_router.delete_document")
@patch("routers.ai_router.ingest_document")
def test_embed_endpoint_잘못된target_collection_VALIDATION_ERROR(mock_ingest, mock_delete):
    mock_ingest.side_effect = ValueError("unknown collection: bogus")

    res = client.post(
        "/ai/rag-documents/embed",
        json={
            "doc_id": "1",
            "title": "문서",
            "doc_type": "LAW",
            "target_collection": "bogus",
            "text": "본문",
        },
    )

    assert res.status_code == 200
    body = res.json()
    assert body["success"] is False
    assert body["error"]["code"] == "VALIDATION_ERROR"


@patch("routers.ai_router.delete_document")
@patch("routers.ai_router.ingest_document")
def test_embed_endpoint_예상치못한예외_LLM_INVALID_OUTPUT폴백(mock_ingest, mock_delete):
    mock_ingest.side_effect = RuntimeError("chroma write failed")

    res = client.post(
        "/ai/rag-documents/embed",
        json={
            "doc_id": "1",
            "title": "문서",
            "doc_type": "LAW",
            "target_collection": "regulations",
            "text": "본문",
        },
    )

    assert res.status_code == 200
    body = res.json()
    assert body["success"] is False
    assert body["error"]["code"] == "LLM_INVALID_OUTPUT"


def test_embed_endpoint_필수필드누락_422():
    res = client.post("/ai/rag-documents/embed", json={"title": "문서"})
    assert res.status_code == 422


if __name__ == "__main__":
    print("Running rag_ingest self-check...")
    test_ingest_document_알수없는컬렉션은ValueError()
    print("OK: rag_ingest self-check passed (run via pytest for mocked cases)")
