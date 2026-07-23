"""nl_search 체인/엔드포인트 검증 — docs/design/ai/nl_search_filter_schema.md §5 fixture 재사용.

- 프롬프트 조립이 query를 UNTRUSTED DATA 마커로 감싸는지(프롬프트 인젝션 방어)
- /ai/nl-search가 structured output 결과를 AIResponse envelope으로 감싸는지
- 빈 질의/500자 초과가 LLM 호출 전에 VALIDATION_ERROR로 즉시 실패하는지(§5.4)
- X-Internal-Service-Token 검증이 X-Internal-Key와 별개로 동작하는지
"""
import os
from unittest.mock import MagicMock, patch

from fastapi.testclient import TestClient

from ai.chains.nl_search_chain import NlSearchResult, _build_prompt
from main import app

client = TestClient(app)


def _result(**overrides) -> NlSearchResult:
    base = dict(
        filters={"type": [], "grade": [], "status": [], "confidenceMin": None},
        unsupported_terms=[],
        clarifying_question=None,
        interpretation_confidence=0.9,
    )
    base.update(overrides)
    return NlSearchResult(**base)


def test_build_prompt_wraps_query_as_untrusted():
    from ai.core.prompt_safety import UNTRUSTED_DATA_BEGIN, UNTRUSTED_DATA_END

    prompt = _build_prompt("D등급 이상 조치 대기 하자")
    assert UNTRUSTED_DATA_BEGIN in prompt
    assert UNTRUSTED_DATA_END in prompt
    assert "D등급 이상 조치 대기 하자" in prompt
    assert prompt.index(UNTRUSTED_DATA_BEGIN) < prompt.index("D등급 이상 조치 대기 하자")


@patch("ai.chains.nl_search_chain.get_llm")
def test_nl_search_endpoint_success(mock_get_llm):
    mock_llm = MagicMock()
    mock_llm.with_structured_output.return_value.invoke.return_value = _result(
        filters={"type": [], "grade": ["D", "E"], "status": ["ACTION_PENDING"], "confidenceMin": None},
    )
    mock_get_llm.return_value = mock_llm

    res = client.post("/ai/nl-search", json={"query": "D등급 이상 조치 대기 하자"})
    assert res.status_code == 200
    body = res.json()
    assert body["success"] is True
    assert body["data"]["filters"]["grade"] == ["D", "E"]
    assert body["data"]["filters"]["status"] == ["ACTION_PENDING"]
    assert body["data"]["clarifying_question"] is None


@patch("ai.chains.nl_search_chain.get_llm")
def test_nl_search_endpoint_ambiguous_query_returns_clarifying_question(mock_get_llm):
    mock_llm = MagicMock()
    mock_llm.with_structured_output.return_value.invoke.return_value = _result(
        clarifying_question="몇 등급 이상을 심각하다고 볼까요?",
        interpretation_confidence=0.3,
    )
    mock_get_llm.return_value = mock_llm

    res = client.post("/ai/nl-search", json={"query": "심각한 거"})
    assert res.status_code == 200
    body = res.json()
    assert body["success"] is True
    assert body["data"]["filters"] == {"type": [], "grade": [], "status": [], "confidenceMin": None}
    assert body["data"]["clarifying_question"] is not None


def test_nl_search_endpoint_empty_query_returns_validation_error_without_llm_call():
    with patch("ai.chains.nl_search_chain.get_llm") as mock_get_llm:
        res = client.post("/ai/nl-search", json={"query": "   "})
        assert res.status_code == 200
        body = res.json()
        assert body["success"] is False
        assert body["error"]["code"] == "VALIDATION_ERROR"
        mock_get_llm.assert_not_called()


def test_nl_search_endpoint_missing_query_returns_validation_error():
    res = client.post("/ai/nl-search", json={})
    assert res.status_code == 200
    body = res.json()
    assert body["success"] is False
    assert body["error"]["code"] == "VALIDATION_ERROR"


def test_nl_search_endpoint_non_string_query_returns_validation_envelope_not_422():
    # 리뷰 P2: query가 문자열이 아닌 값(숫자 등)이어도 FastAPI 기본 422가 아니라 동일한
    # AIResponse.fail(VALIDATION_ERROR) envelope으로 응답해야 한다(설계 §2.1).
    with patch("ai.chains.nl_search_chain.get_llm") as mock_get_llm:
        res = client.post("/ai/nl-search", json={"query": 12345})
        assert res.status_code == 200
        body = res.json()
        assert body["success"] is False
        assert body["error"]["code"] == "VALIDATION_ERROR"
        mock_get_llm.assert_not_called()


def test_nl_search_endpoint_query_too_long_returns_validation_error_without_llm_call():
    with patch("ai.chains.nl_search_chain.get_llm") as mock_get_llm:
        res = client.post("/ai/nl-search", json={"query": "가" * 501})
        assert res.status_code == 200
        body = res.json()
        assert body["success"] is False
        assert body["error"]["code"] == "VALIDATION_ERROR"
        mock_get_llm.assert_not_called()


@patch("ai.chains.nl_search_chain.get_llm")
def test_nl_search_endpoint_llm_failure_returns_error_envelope(mock_get_llm):
    mock_get_llm.side_effect = KeyError("HF_API_TOKEN")

    res = client.post("/ai/nl-search", json={"query": "균열만 보여줘"})
    assert res.status_code == 200
    body = res.json()
    assert body["success"] is False
    assert body["error"]["code"] == "LLM_INVALID_OUTPUT"


# ── X-Internal-Service-Token (X-Internal-Key와 별개 검증, deps.verify_internal_service_token) ──


@patch.dict(os.environ, {"AI_INTERNAL_SERVICE_TOKEN": "secret-svc"})
def test_nl_search_missing_service_token_returns_401():
    res = client.post("/ai/nl-search", json={"query": "균열만 보여줘"})
    assert res.status_code == 401


@patch.dict(os.environ, {"AI_INTERNAL_SERVICE_TOKEN": "secret-svc"})
def test_nl_search_wrong_service_token_returns_401():
    res = client.post(
        "/ai/nl-search",
        json={"query": "균열만 보여줘"},
        headers={"X-Internal-Service-Token": "wrong"},
    )
    assert res.status_code == 401


@patch.dict(os.environ, {"AI_INTERNAL_SERVICE_TOKEN": "secret-svc"})
@patch("ai.chains.nl_search_chain.get_llm")
def test_nl_search_correct_service_token_succeeds_without_internal_key(mock_get_llm):
    # X-Internal-Key는 설정/전달하지 않아도 통과해야 한다 — 이 라우트는 별도 토큰 체계를 쓴다.
    mock_llm = MagicMock()
    mock_llm.with_structured_output.return_value.invoke.return_value = _result()
    mock_get_llm.return_value = mock_llm

    res = client.post(
        "/ai/nl-search",
        json={"query": "균열만 보여줘"},
        headers={"X-Internal-Service-Token": "secret-svc"},
    )
    assert res.status_code == 200
    assert res.json()["success"] is True


def test_nl_search_no_token_configured_allows_no_header(monkeypatch):
    monkeypatch.delenv("AI_INTERNAL_SERVICE_TOKEN", raising=False)
    with patch("ai.chains.nl_search_chain.get_llm") as mock_get_llm:
        mock_llm = MagicMock()
        mock_llm.with_structured_output.return_value.invoke.return_value = _result()
        mock_get_llm.return_value = mock_llm

        res = client.post("/ai/nl-search", json={"query": "균열만 보여줘"})
        assert res.status_code == 200
        assert res.json()["success"] is True
