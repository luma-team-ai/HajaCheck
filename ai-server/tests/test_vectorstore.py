"""Chroma vectorstore/embeddings 팩토리 검증.

- get_embeddings()가 HuggingFaceEmbeddings(BAAI/bge-m3, CPU)를 생성
- get_vectorstore()가 LangChain Chroma 객체를 반환 (similarity_search 인터페이스 보유)
- 알 수 없는 컬렉션명은 ValueError
"""
import os
from unittest.mock import MagicMock, patch

import pytest


@patch("ai.core.embeddings.HuggingFaceEmbeddings")
def test_get_embeddings_default_model(mock_hf_embeddings):
    from ai.core import embeddings

    embeddings.get_embeddings.cache_clear()
    mock_hf_embeddings.return_value = MagicMock()

    embeddings.get_embeddings()

    mock_hf_embeddings.assert_called_once_with(
        model_name="BAAI/bge-m3", model_kwargs={"device": "cpu"}
    )


@patch.dict(os.environ, {"CHROMA_PERSIST_DIR": "/tmp/chroma-test"})
@patch("ai.core.vectorstore.get_embeddings")
@patch("ai.core.vectorstore.Chroma")
@patch("ai.core.vectorstore._client")
def test_get_vectorstore_creates_chroma(
    mock_client_factory, mock_chroma_class, mock_get_embeddings
):
    from ai.core import vectorstore

    mock_client_instance = MagicMock()
    mock_client_factory.return_value = mock_client_instance
    mock_embeddings = MagicMock()
    mock_get_embeddings.return_value = mock_embeddings

    vectorstore.get_vectorstore(vectorstore.COLLECTION_REGULATIONS)

    # Chroma 생성자가 올바른 인자로 호출됐는지 검증
    mock_chroma_class.assert_called_once()
    call_kwargs = mock_chroma_class.call_args.kwargs
    assert call_kwargs["client"] is mock_client_instance
    assert call_kwargs["collection_name"] == "regulations"
    assert call_kwargs["embedding_function"] is mock_embeddings
    assert call_kwargs["collection_metadata"] == {"hnsw:space": "cosine"}
    # embeddings.get_embeddings()가 실제로 호출됐는지 확인
    mock_get_embeddings.assert_called_once()


@patch("ai.core.embeddings.HuggingFaceEmbeddings")
def test_get_embeddings_env_override(mock_hf_embeddings):
    """EMBEDDING_MODEL 오버라이드가 실제로 먹는지 — 모듈 임포트 뒤 주입해도 반영돼야 한다."""
    from ai.core import embeddings

    try:
        with patch.dict(os.environ, {"EMBEDDING_MODEL": "intfloat/multilingual-e5-small"}):
            embeddings.get_embeddings.cache_clear()
            embeddings.get_embeddings()

        mock_hf_embeddings.assert_called_once_with(
            model_name="intfloat/multilingual-e5-small", model_kwargs={"device": "cpu"}
        )
    finally:
        embeddings.get_embeddings.cache_clear()


@patch("ai.core.vectorstore.chromadb.PersistentClient")
def test_chroma_persist_dir_read_at_call_time_not_import_time(mock_persistent_client):
    """env는 **호출 시점**에 읽어야 한다 — 임포트 순서에 좌우되면 안 된다.

    회귀 방지: CHROMA_PERSIST_DIR을 모듈 최상단에서 os.getenv로 읽으면 값이 첫 임포트
    시점에 고정된다. 그러면 다른 테스트가 먼저 main→ai_router→vectorstore를 임포트한
    경우(test_internal_key.py가 실제로 그렇다) 이 주입이 통째로 무시된다.
    아래는 그 상황을 결정적으로 재현한다 — env 주입 **전에** 모듈을 먼저 임포트한다.
    """
    from ai.core import vectorstore

    try:
        with patch.dict(os.environ, {"CHROMA_PERSIST_DIR": "/tmp/chroma-late"}):
            vectorstore._client.cache_clear()
            vectorstore._client()

        mock_persistent_client.assert_called_once_with(path="/tmp/chroma-late")
    finally:
        vectorstore._client.cache_clear()


def test_get_vectorstore_unknown_collection_raises():
    from ai.core import vectorstore

    with pytest.raises(ValueError, match="unknown collection"):
        vectorstore.get_vectorstore("not_a_real_collection")


def test_get_vectorstore_real_chromadb_add_query(tmp_path):
    """실 chromadb 통합 — LangChain Chroma similarity_search를 검증.

    LangChain Chroma 객체가 get_vectorstore에서 반환되고,
    similarity_search(query, k=N) / add_texts() 등 LangChain 인터페이스로
    동작하는지 확인한다. embeddings.py의 모델 다운로드는 회피하기 위해
    get_embeddings만 stub한다.
    """
    from ai.core import vectorstore

    called = {"n_embed_documents": 0, "n_embed_query": 0}

    def fake_embed_documents(docs):
        called["n_embed_documents"] += 1
        # 각 문서를 8차원 벡터로 변환 (임의의 임베딩)
        return [[float(len(d) % 7)] + [0.1 * i for i in range(7)] for d in docs]

    def fake_embed_query(query):
        called["n_embed_query"] += 1
        # 쿼리를 8차원 벡터로 변환
        return [float(len(query) % 7)] + [0.1 * i for i in range(7)]

    fake_embeddings = MagicMock()
    fake_embeddings.embed_documents = fake_embed_documents
    fake_embeddings.embed_query = fake_embed_query

    with (
        patch("ai.core.vectorstore.get_embeddings", return_value=fake_embeddings),
        patch.dict(os.environ, {"CHROMA_PERSIST_DIR": str(tmp_path)}),
    ):
        vectorstore._client.cache_clear()

        # get_vectorstore는 LangChain Chroma 객체 반환
        vs = vectorstore.get_vectorstore(vectorstore.COLLECTION_REGULATIONS)

        # LangChain 인터페이스: add_texts(texts, ids=[...])
        vs.add_texts(texts=["균열 설명", "누수 조항"], ids=["a", "b"])

        # LangChain 인터페이스: similarity_search(query, k=N)
        # → Document 객체 리스트 반환
        results = vs.similarity_search("균열", k=1)

        # 검색 품질(최근접 정확도)은 실 임베딩 몫 — 여기선 add_texts/similarity_search가 무예외로 돌고
        # embeddings 어댑터가 embed_documents/embed_query를 호출하는 계약만 고정한다.
        assert len(results) >= 1
        assert called["n_embed_documents"] > 0  # add_texts에서 embed_documents 호출
        assert called["n_embed_query"] > 0  # similarity_search에서 embed_query 호출
        # 반환된 객체는 LangChain Document 타입
        from langchain_core.documents import Document
        assert all(isinstance(doc, Document) for doc in results)

        # 재오픈 — 컬렉션이 persist되고 복원되는지 확인
        vectorstore._client.cache_clear()
        reopened = vectorstore.get_vectorstore(vectorstore.COLLECTION_REGULATIONS)
        # 재오픈 후에도 이전 추가된 문서가 있어야 함
        reopened_results = reopened.similarity_search("누수", k=1)
        assert len(reopened_results) >= 1

    vectorstore._client.cache_clear()


if __name__ == "__main__":
    print("Running vectorstore/embeddings tests...")
    test_get_vectorstore_unknown_collection_raises()
    print("OK: vectorstore tests passed (run via pytest for mocked cases)")
