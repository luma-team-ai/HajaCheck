"""rag_chat 체인/엔드포인트 검증 (실제 Chroma/HF/Redis 호출 없이 get_vectorstore·get_llm·
get_redis_client만 모킹). test_defect_explain.py 패턴을 따른다.

- _render_locator: article/clause/page 유무별 렌더링 규칙(설계 rag_chroma_schema.md §7)
- 정상 응답: sources가 LLM 출력과 무관하게 검색 metadata에서 결정적으로 구성되는지
- 검색 0건: RAG_NO_RESULT 에러 envelope(캐시 저장 안 함)
- Redis 캐시 히트/저장: 키·TTL·직렬화 값, 히트 시 벡터스토어/LLM 미호출
- LLM 예외 시 /ai/rag-chat 이 서버를 죽이지 않고 AIResponse.fail 로 응답하는지
"""
import json
from unittest.mock import MagicMock, patch

from fastapi.testclient import TestClient
from langchain_core.documents import Document

from ai.chains.rag_chat_chain import (
    RAG_CHAT_CACHE_PREFIX,
    _build_sources,
    _cache_key,
    _render_locator,
    _RagChatAnswer,
)
from ai.core.llm_client import CACHE_TTL_SECONDS
from main import app

client = TestClient(app)


def _doc(doc_id="42", chunk_index=3, source="시설물의 안전 및 유지관리에 관한 특별법", **extra_metadata):
    metadata = {"doc_id": doc_id, "source": source, "chunk_index": chunk_index, **extra_metadata}
    return Document(page_content="관리주체는 시설물의 안전점검을 정기적으로 실시하여야 한다.", metadata=metadata)


# ---------------------------------------------------------------------------
# _render_locator — 설계 rag_chroma_schema.md §7 4케이스
# ---------------------------------------------------------------------------


def test_render_locator_article_only():
    assert _render_locator({"article": "제12조"}) == "제12조"


def test_render_locator_article_and_clause():
    assert _render_locator({"article": "제12조", "clause": "①"}) == "제12조 ①"


def test_render_locator_page_only():
    assert _render_locator({"page": 12}) == "12페이지"


def test_render_locator_fallback_when_neither_article_nor_page():
    assert _render_locator({}) == "문서 전체"


# ---------------------------------------------------------------------------
# _build_sources — rag_chroma_schema.md §4.2 매핑
# ---------------------------------------------------------------------------


def test_build_sources_maps_metadata_deterministically():
    docs = [_doc(doc_id="42", chunk_index=3, article="제12조")]
    sources = _build_sources(docs)

    assert len(sources) == 1
    source = sources[0]
    assert source.doc_id == "42"
    assert source.title == "시설물의 안전 및 유지관리에 관한 특별법"
    assert source.collection == "regulations"
    assert source.locator == "제12조"
    assert source.snippet == "관리주체는 시설물의 안전점검을 정기적으로 실시하여야 한다."
    assert source.chunk_ref == "42_3"


# ---------------------------------------------------------------------------
# /ai/rag-chat 엔드포인트
# ---------------------------------------------------------------------------


@patch("ai.chains.rag_chat_chain.get_redis_client")
@patch("ai.chains.rag_chat_chain.get_llm")
@patch("ai.chains.rag_chat_chain.get_vectorstore")
def test_rag_chat_endpoint_success_sources_ignore_llm_output(
    mock_get_vectorstore, mock_get_llm, mock_get_redis_client
):
    """LLM mock이 sources와 무관한 answer만 주더라도, 응답 sources는 검색 metadata에서만
    구성됨을 구조적으로 검증한다(_RagChatAnswer에 sources 필드 자체가 없음)."""
    mock_redis = MagicMock()
    mock_redis.get.return_value = None
    mock_get_redis_client.return_value = mock_redis

    mock_vectorstore = MagicMock()
    mock_vectorstore.similarity_search.return_value = [
        _doc(doc_id="42", chunk_index=3, article="제12조")
    ]
    mock_get_vectorstore.return_value = mock_vectorstore

    mock_llm = MagicMock()
    mock_llm.with_structured_output.return_value.invoke.return_value = _RagChatAnswer(
        answer="균열 보수는 손상 정도와 구조 안전성 평가 결과에 따라 보수 공법을 선택합니다."
    )
    mock_get_llm.return_value = mock_llm

    res = client.post("/ai/rag-chat", json={"question": "균열 보수 기준은?"})

    assert res.status_code == 200
    body = res.json()
    assert body["success"] is True
    assert body["data"]["answer"].startswith("균열 보수는")
    assert body["data"]["sources"] == [
        {
            "doc_id": "42",
            "title": "시설물의 안전 및 유지관리에 관한 특별법",
            "collection": "regulations",
            "locator": "제12조",
            "snippet": "관리주체는 시설물의 안전점검을 정기적으로 실시하여야 한다.",
            "chunk_ref": "42_3",
        }
    ]
    mock_vectorstore.similarity_search.assert_called_once_with("균열 보수 기준은?", k=4)


@patch("ai.chains.rag_chat_chain.get_redis_client")
@patch("ai.chains.rag_chat_chain.get_llm")
@patch("ai.chains.rag_chat_chain.get_vectorstore")
def test_rag_chat_endpoint_no_result_returns_rag_no_result_and_skips_cache_write(
    mock_get_vectorstore, mock_get_llm, mock_get_redis_client
):
    mock_redis = MagicMock()
    mock_redis.get.return_value = None
    mock_get_redis_client.return_value = mock_redis

    mock_vectorstore = MagicMock()
    mock_vectorstore.similarity_search.return_value = []
    mock_get_vectorstore.return_value = mock_vectorstore

    res = client.post("/ai/rag-chat", json={"question": "존재하지 않는 질의"})

    assert res.status_code == 200
    body = res.json()
    assert body["success"] is False
    assert body["error"]["code"] == "RAG_NO_RESULT"
    mock_get_llm.assert_not_called()
    mock_redis.setex.assert_not_called()


@patch("ai.chains.rag_chat_chain.get_redis_client")
@patch("ai.chains.rag_chat_chain.get_llm")
@patch("ai.chains.rag_chat_chain.get_vectorstore")
def test_rag_chat_cache_hit_skips_vectorstore_and_llm(
    mock_get_vectorstore, mock_get_llm, mock_get_redis_client
):
    question = "균열 보수 기준은?"
    cached_payload = {
        "answer": "캐시된 답변",
        "sources": [
            {
                "doc_id": "42",
                "title": "시설물의 안전 및 유지관리에 관한 특별법",
                "collection": "regulations",
                "locator": "제12조",
                "snippet": "관리주체는 시설물의 안전점검을 정기적으로 실시하여야 한다.",
                "chunk_ref": "42_3",
            }
        ],
    }
    mock_redis = MagicMock()
    mock_redis.get.return_value = json.dumps(cached_payload)
    mock_get_redis_client.return_value = mock_redis

    res = client.post("/ai/rag-chat", json={"question": question})

    assert res.status_code == 200
    body = res.json()
    assert body["success"] is True
    assert body["data"] == cached_payload
    mock_redis.get.assert_called_once_with(_cache_key(question))
    mock_get_vectorstore.assert_not_called()
    mock_get_llm.assert_not_called()


@patch("ai.chains.rag_chat_chain.get_redis_client")
@patch("ai.chains.rag_chat_chain.get_llm")
@patch("ai.chains.rag_chat_chain.get_vectorstore")
def test_rag_chat_success_writes_cache_with_expected_key_ttl_and_payload(
    mock_get_vectorstore, mock_get_llm, mock_get_redis_client
):
    question = "균열 보수 기준은?"

    mock_redis = MagicMock()
    mock_redis.get.return_value = None
    mock_get_redis_client.return_value = mock_redis

    mock_vectorstore = MagicMock()
    mock_vectorstore.similarity_search.return_value = [
        _doc(doc_id="42", chunk_index=3, article="제12조")
    ]
    mock_get_vectorstore.return_value = mock_vectorstore

    mock_llm = MagicMock()
    mock_llm.with_structured_output.return_value.invoke.return_value = _RagChatAnswer(
        answer="균열 보수는 손상 정도와 구조 안전성 평가 결과에 따라 보수 공법을 선택합니다."
    )
    mock_get_llm.return_value = mock_llm

    res = client.post("/ai/rag-chat", json={"question": question})

    assert res.status_code == 200
    mock_redis.setex.assert_called_once()
    call_args = mock_redis.setex.call_args
    cache_key, ttl, cached_value = call_args[0]
    assert cache_key == _cache_key(question)
    assert cache_key.startswith(RAG_CHAT_CACHE_PREFIX)
    assert ttl == CACHE_TTL_SECONDS

    stored = json.loads(cached_value)
    assert stored["answer"] == "균열 보수는 손상 정도와 구조 안전성 평가 결과에 따라 보수 공법을 선택합니다."
    assert stored["sources"][0]["chunk_ref"] == "42_3"


@patch("ai.chains.rag_chat_chain.get_redis_client")
def test_rag_chat_endpoint_llm_failure_returns_error_envelope(mock_get_redis_client):
    mock_get_redis_client.side_effect = KeyError("REDIS_URL")

    res = client.post("/ai/rag-chat", json={"question": "균열 보수 기준은?"})

    assert res.status_code == 200
    body = res.json()
    assert body["success"] is False
    assert body["error"]["code"] == "LLM_INVALID_OUTPUT"


if __name__ == "__main__":
    test_render_locator_article_only()
    test_render_locator_article_and_clause()
    test_render_locator_page_only()
    test_render_locator_fallback_when_neither_article_nor_page()
    test_build_sources_maps_metadata_deterministically()
    print("OK: rag_chat_chain self-check passed")
