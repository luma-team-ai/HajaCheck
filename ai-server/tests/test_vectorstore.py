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
    # 어댑터가 embeddings.py의 embed_documents로 위임하는지 확인
    # (chromadb EmbeddingFunction 베이스가 출력을 numpy float32로 정규화하므로 값 기준 비교)
    emb_out = call_kwargs["embedding_function"](["a", "b"])
    assert len(emb_out) == 1
    assert [float(v) for v in emb_out[0]] == pytest.approx([0.1])


def test_get_vectorstore_unknown_collection_raises():
    from ai.core import vectorstore

    with pytest.raises(ValueError, match="unknown collection"):
        vectorstore.get_vectorstore("not_a_real_collection")


def test_get_vectorstore_real_chromadb_add_query(tmp_path):
    """실 chromadb 통합 — mock이 가리던 어댑터↔chromadb 계약을 고정.

    embedding_function으로 커스텀 어댑터를 넘겼을 때 get_or_create_collection이
    예외 없이 등록되고(add/query 시 embed_documents 위임), 컬렉션 재오픈 시
    EF 검증(name()/config)도 통과하는지 실제 PersistentClient로 확인.
    embeddings.py의 모델 다운로드는 회피하기 위해 get_embeddings만 stub한다.
    """
    from ai.core import vectorstore

    called = {"n": 0}

    def fake_embed(docs):
        called["n"] += 1
        return [[float(len(d) % 7)] + [0.1 * i for i in range(7)] for d in docs]

    fake = MagicMock(embed_documents=fake_embed)

    with patch("ai.core.vectorstore.get_embeddings", return_value=fake):
        vectorstore.CHROMA_PERSIST_DIR = str(tmp_path)
        vectorstore._client.cache_clear()

        col = vectorstore.get_vectorstore(vectorstore.COLLECTION_REGULATIONS)
        col.add(ids=["a", "b"], documents=["균열 설명", "누수 조항"])
        result = col.query(query_texts=["균열"], n_results=1)

        # 검색 품질(최근접 정확도)은 실 임베딩 몫 — 여기선 add/query가 무예외로 돌고
        # 어댑터가 embed_documents로 위임되는 계약만 고정한다.
        assert col.count() == 2
        assert result["ids"][0][0] in {"a", "b"}
        assert called["n"] > 0  # 어댑터가 실제로 embed_documents를 호출함

        # 재오픈 — EF name()/config 검증 경로가 예외 없이 통과하는지
        reopened = vectorstore.get_vectorstore(vectorstore.COLLECTION_REGULATIONS)
        assert reopened.count() == 2

    vectorstore._client.cache_clear()


if __name__ == "__main__":
    print("Running vectorstore/embeddings tests...")
    test_get_vectorstore_unknown_collection_raises()
    print("OK: vectorstore tests passed (run via pytest for mocked cases)")
