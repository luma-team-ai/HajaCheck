"""점검 목록 자연어 검색 체인·정규화·내부 엔드포인트 검증."""

import os
from datetime import date
from unittest.mock import MagicMock, patch

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from ai.chains.nl_search_chain import (
    DateIntent,
    DefectCountIntent,
    NlSearchIntentV2,
    RoundIntent,
    _build_prompt,
    normalize_nl_search_intent,
    run_nl_search_chain,
)
from ai.core.llm_client import _StructuredLLM
from main import app

client = TestClient(app)


def _intent(**overrides) -> NlSearchIntentV2:
    base = dict(
        intentVersion="2",
        type=[],
        grade=[],
        defectStatus=[],
        confidenceMin=None,
        inspectionType=[],
        inspectionStatus=[],
        inspectionDate=None,
        roundNo=None,
        defectCount=None,
        unsupported_terms=[],
        clarifying_question=None,
        interpretation_confidence=0.9,
    )
    base.update(overrides)
    return NlSearchIntentV2(**base)


def _mock_intent(mock_get_llm, intent: NlSearchIntentV2) -> MagicMock:
    mock_llm = MagicMock()
    mock_llm.with_structured_output.return_value.invoke.return_value = intent
    mock_get_llm.return_value = mock_llm
    return mock_llm


def test_build_prompt_wraps_query_as_untrusted_and_forbids_relative_date_resolution():
    from ai.core.prompt_safety import UNTRUSTED_DATA_BEGIN, UNTRUSTED_DATA_END

    prompt = _build_prompt("지난 두 달간의 1회차 점검 알려줘")

    assert UNTRUSTED_DATA_BEGIN in prompt
    assert UNTRUSTED_DATA_END in prompt
    assert "지난 두 달간의 1회차 점검 알려줘" in prompt
    assert prompt.index(UNTRUSTED_DATA_BEGIN) < prompt.index("지난 두 달간의 1회차 점검 알려줘")
    assert "실제 날짜를 계산하지 마라" in prompt


def test_prompt_preserves_v1_grade_status_confidence_and_ambiguity_rules():
    prompt = _build_prompt("규칙 회귀 검증")

    assert '"D등급 이상"→["D","E"]' in prompt
    assert "콘크리트 박리" in prompt
    assert "누수 흔적" in prompt
    assert "철근 드러남" in prompt
    assert "도장 벗겨짐" in prompt
    assert "신규/미확인/신규 탐지/AI 탐지→DETECTED" in prompt
    assert "검수확정/조치대기/조치 대기/대기중/조치 필요→CONFIRMED" in prompt
    assert '"80% 이상"→0.8' in prompt
    assert "신뢰도 상한/미만은 지원하지 않으므로" in prompt
    assert '"심각한", "위험한"만으로 등급을 추측하지 말고' in prompt
    assert '"검수 완료"만 있어 하자 상태인지 점검 상태인지 불명확하면' in prompt
    assert '"완료된 점검", "진행 중인 점검"처럼 단계를 특정하지 않으면' in prompt


def test_v2_intent_version_is_required_discriminator():
    payload = {"interpretation_confidence": 0.9}

    with pytest.raises(ValidationError):
        NlSearchIntentV2(**payload)
    with pytest.raises(ValidationError):
        NlSearchIntentV2(**payload, intentVersion="1")

    assert "intentVersion" in NlSearchIntentV2.model_json_schema()["required"]


def test_normalizer_resolves_two_months_and_exact_first_round_inclusively():
    intent = _intent(
        inspectionDate={"kind": "ROLLING_PAST", "amount": 2, "unit": "MONTH"},
        roundNo={"operator": "EXACT", "value": 1},
    )

    result = normalize_nl_search_intent(intent, date(2026, 7, 28))

    assert result.filters.inspectionDateFrom == date(2026, 5, 28)
    assert result.filters.inspectionDateTo == date(2026, 7, 28)
    assert result.filters.roundNoMin == 1
    assert result.filters.roundNoMax == 1


def test_normalizer_clamps_month_end_without_losing_inclusive_end():
    intent = _intent(inspectionDate={"kind": "ROLLING_PAST", "amount": 1, "unit": "MONTH"})

    result = normalize_nl_search_intent(intent, date(2026, 3, 31))

    assert result.filters.inspectionDateFrom == date(2026, 2, 28)
    assert result.filters.inspectionDateTo == date(2026, 3, 31)


@patch("ai.chains.nl_search_chain.get_llm")
def test_chain_resolves_same_cached_intent_against_each_request_reference_date(mock_get_llm):
    mock_llm = _mock_intent(
        mock_get_llm,
        _intent(inspectionDate={"kind": "ROLLING_PAST", "amount": 2, "unit": "MONTH"}),
    )

    july_result = run_nl_search_chain("지난 두 달간", date(2026, 7, 28))
    august_result = run_nl_search_chain("지난 두 달간", date(2026, 8, 28))

    assert july_result.filters.inspectionDateFrom == date(2026, 5, 28)
    assert august_result.filters.inspectionDateFrom == date(2026, 6, 28)
    prompts = [call.args[0] for call in mock_llm.with_structured_output.return_value.invoke.call_args_list]
    assert prompts[0] == prompts[1]


def test_normalizer_maps_all_new_axes_and_deduplicates_enum_lists():
    intent = _intent(
        type=["CRACK", "CRACK"],
        grade=["D", "E"],
        defectStatus=["CONFIRMED"],
        inspectionType=["REGULAR", "DETAILED"],
        inspectionStatus=["REVIEWED"],
        inspectionDate={"kind": "ABSOLUTE_RANGE", "dateFrom": "2026-07-01", "dateTo": "2026-07-20"},
        roundNo={"operator": "BETWEEN", "value": 2, "maxValue": 4},
        defectCount={"operator": "GTE", "value": 3},
    )

    result = normalize_nl_search_intent(intent, date(2026, 7, 28))

    assert result.filters.type == ["CRACK"]
    assert result.filters.inspectionType == ["REGULAR", "DETAILED"]
    assert result.filters.inspectionStatus == ["REVIEWED"]
    assert result.filters.inspectionDateFrom == date(2026, 7, 1)
    assert result.filters.inspectionDateTo == date(2026, 7, 20)
    assert (result.filters.roundNoMin, result.filters.roundNoMax) == (2, 4)
    assert (result.filters.defectCountMin, result.filters.defectCountMax) == (3, None)


def test_normalizer_preserves_v1_filters_unsupported_terms_and_clarification():
    intent = _intent(
        type=["CRACK"],
        grade=["D", "E"],
        defectStatus=["CONFIRMED"],
        confidenceMin=0.8,
        unsupported_terms=["신뢰도 80% 이하"],
        clarifying_question="몇 등급 이상을 심각하다고 볼까요?",
        interpretation_confidence=0.3,
    )

    result = normalize_nl_search_intent(intent, date(2026, 7, 28))

    assert result.filters.type == ["CRACK"]
    assert result.filters.grade == ["D", "E"]
    assert result.filters.status == ["CONFIRMED"]
    assert result.filters.confidenceMin == 0.8
    assert result.unsupported_terms == ["신뢰도 80% 이하"]
    assert result.clarifying_question == "몇 등급 이상을 심각하다고 볼까요?"


def test_structured_cache_identity_uses_v2_schema_name():
    structured = _StructuredLLM(MagicMock(), NlSearchIntentV2, cache_namespace="test")

    assert ":NlSearchIntentV2:" in structured._cache_key("same prompt")


@pytest.mark.parametrize(
    "model",
    [
        lambda: DateIntent(kind="ABSOLUTE_RANGE", dateFrom="2026-07-20", dateTo="2026-07-01"),
        lambda: DateIntent(kind="ROLLING_PAST", amount=2),
        lambda: RoundIntent(operator="BETWEEN", value=3, maxValue=2),
        lambda: DefectCountIntent(operator="EXACT", value=3, maxValue=4),
    ],
)
def test_intent_models_reject_inconsistent_shapes(model):
    with pytest.raises(ValidationError):
        model()


@patch("ai.chains.nl_search_chain.get_llm")
def test_endpoint_returns_resolved_filters_and_uses_v2_schema(mock_get_llm):
    mock_llm = _mock_intent(
        mock_get_llm,
        _intent(
            inspectionDate={"kind": "ROLLING_PAST", "amount": 2, "unit": "MONTH"},
            roundNo={"operator": "EXACT", "value": 1},
        ),
    )

    res = client.post(
        "/ai/nl-search",
        json={"query": "지난 두 달간의 1회차 점검 알려줘", "referenceDate": "2026-07-28"},
    )

    assert res.status_code == 200
    assert res.json()["data"]["filters"]["inspectionDateFrom"] == "2026-05-28"
    assert res.json()["data"]["filters"]["inspectionDateTo"] == "2026-07-28"
    assert res.json()["data"]["filters"]["roundNoMin"] == 1
    assert res.json()["data"]["filters"]["roundNoMax"] == 1
    schema = mock_llm.with_structured_output.call_args.args[0]
    assert schema is NlSearchIntentV2


@patch("ai.chains.nl_search_chain.get_llm")
def test_endpoint_preserves_clarification_without_guessing_ambiguous_axis(mock_get_llm):
    _mock_intent(
        mock_get_llm,
        _intent(
            clarifying_question="하자 검수 완료와 점검 검수 완료 중 어느 쪽을 뜻하시나요?",
            interpretation_confidence=0.3,
        ),
    )

    res = client.post(
        "/ai/nl-search",
        json={"query": "검수 완료", "referenceDate": "2026-07-28"},
    )

    body = res.json()
    assert body["success"] is True
    assert body["data"]["filters"]["status"] == []
    assert body["data"]["filters"]["inspectionStatus"] == []
    assert body["data"]["clarifying_question"] is not None


def test_endpoint_invalid_reference_date_returns_validation_error_without_llm_call():
    with patch("ai.chains.nl_search_chain.get_llm") as mock_get_llm:
        res = client.post(
            "/ai/nl-search",
            json={"query": "균열", "referenceDate": "2026-7-28"},
        )

    assert res.status_code == 200
    assert res.json()["error"]["code"] == "VALIDATION_ERROR"
    mock_get_llm.assert_not_called()


def test_endpoint_empty_query_returns_validation_error_without_llm_call():
    with patch("ai.chains.nl_search_chain.get_llm") as mock_get_llm:
        res = client.post("/ai/nl-search", json={"query": "   "})

    assert res.status_code == 200
    assert res.json()["error"]["code"] == "VALIDATION_ERROR"
    mock_get_llm.assert_not_called()


def test_endpoint_missing_or_non_string_query_returns_validation_error():
    for payload in ({}, {"query": 12345}):
        res = client.post("/ai/nl-search", json=payload)
        assert res.status_code == 200
        assert res.json()["error"]["code"] == "VALIDATION_ERROR"


def test_endpoint_query_too_long_returns_validation_error_without_llm_call():
    with patch("ai.chains.nl_search_chain.get_llm") as mock_get_llm:
        res = client.post("/ai/nl-search", json={"query": "가" * 501})

    assert res.status_code == 200
    assert res.json()["error"]["code"] == "VALIDATION_ERROR"
    mock_get_llm.assert_not_called()


@patch("ai.chains.nl_search_chain.get_llm")
def test_endpoint_llm_failure_returns_error_envelope(mock_get_llm):
    mock_get_llm.side_effect = KeyError("HF_API_TOKEN")

    res = client.post("/ai/nl-search", json={"query": "균열만 보여줘"})

    assert res.status_code == 200
    assert res.json()["error"]["code"] == "LLM_INVALID_OUTPUT"


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
    _mock_intent(mock_get_llm, _intent())

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
        _mock_intent(mock_get_llm, _intent())
        res = client.post("/ai/nl-search", json={"query": "균열만 보여줘"})

    assert res.status_code == 200
    assert res.json()["success"] is True
