"""Chroma vectorstore/embeddings 팩토리 검증.

- get_embeddings()가 HuggingFaceEmbeddings(BAAI/bge-m3, CPU)를 생성
- get_vectorstore()가 PersistentClient + get_or_create_collection을 올바른 인자로 호출
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
@patch("ai.core.vectorstore.chromadb.PersistentClient")
def test_get_vectorstore_creates_collection(mock_persistent_client, mock_get_embeddings):
    from ai.core import vectorstore

    vectorstore._client.cache_clear()
    mock_client_instance = MagicMock()
    mock_persistent_client.return_value = mock_client_instance
    mock_get_embeddings.return_value = MagicMock(embed_documents=MagicMock(return_value=[[0.1]]))

    vectorstore.get_vectorstore(vectorstore.COLLECTION_REGULATIONS)

    mock_persistent_client.assert_called_once_with(path="/tmp/chroma-test")
    mock_client_instance.get_or_create_collection.assert_called_once()
    call_kwargs = mock_client_instance.get_or_create_collection.call_args.kwargs
    assert call_kwargs["name"] == "regulations"
    assert call_kwargs["metadata"] == {"hnsw:space": "cosine"}
    # 어댑터가 embeddings.py의 embed_documents를 그대로 위임하는지 확인
    assert call_kwargs["embedding_function"](["a", "b"]) == [[0.1]]


def test_get_vectorstore_unknown_collection_raises():
    from ai.core import vectorstore

    with pytest.raises(ValueError, match="unknown collection"):
        vectorstore.get_vectorstore("not_a_real_collection")


if __name__ == "__main__":
    print("Running vectorstore/embeddings tests...")
    test_get_vectorstore_unknown_collection_raises()
    print("OK: vectorstore tests passed (run via pytest for mocked cases)")
