"""report_chain / /ai/report 엔드포인트 검증 (실제 HF/Redis/Chroma 호출 없이 get_llm·get_vectorstore만 모킹).

- 4개 섹션 프롬프트 조립이 실제 prompts/report_*.md 파일을 읽어 정상 조립되는지
- /ai/report 이 4섹션 + grounding_ok 를 contract.md 필드명 그대로 AIResponse envelope으로 감싸는지
- LLM 예외 시 서버가 죽지 않고 AIResponse.fail 로 응답하는지
- Grounding 불일치 시 REGENERATE 재시도 흐름이 동작하는지
- detail 섹션 items 개수가 confirmed_defects와 다르면 실패 처리되는지
- vectorstore.get_vectorstore()가 NotImplementedError를 던져도 recommendation이 "관련 근거 없음"으로 폴백되는지
"""
from unittest.mock import MagicMock, patch

import pytest
from fastapi.testclient import TestClient
from langchain_core.exceptions import OutputParserException
from pydantic import ValidationError

from ai.chains.report_chain import (
    DefectDetailItem,
    RecommendationItem,
    ReportDetail,
    ReportOverview,
    ReportRecommendation,
    ReportSummary,
    _build_prompt_detail,
    _build_prompt_overview,
    _build_prompt_recommendation,
    _build_prompt_summary,
    full_grade_counts,
    run_report_chain,
)
from main import app

client = TestClient(app)


def _sample_facility_info() -> dict:
    return {"name": "Haja APT", "location": "서울시"}


def _sample_defects() -> list[dict]:
    return [
        {
            "defect_type": "균열",
            "location": "1동 1층 기둥",
            "severity_grade": "B",
            "description": "기둥 표면 수평 균열",
        }
    ]


def _sample_overview() -> ReportOverview:
    return ReportOverview(
        purpose="하자의 발생 현황을 체계적으로 조사하고 조치방안을 제안하기 위함",
        facility_summary="Haja APT 101동 등 서울시 소재 공동주택",
        scope="전체 외벽 및 지하주차장 정밀 점검",
    )


def _sample_summary(total_count: int = 1) -> ReportSummary:
    return ReportSummary(
        overall_opinion="전반적으로 양호하나 일부 결함이 확인됨",
        total_count=total_count,
        count_by_grade={"A": 0, "B": 1, "C": 0, "D": 0, "E": 0},
        key_findings=["1동 기둥 균열 발생"],
    )


def _sample_detail(n: int = 1) -> ReportDetail:
    items = [
        DefectDetailItem(
            defect_type="균열",
            location="1동 1층 기둥",
            severity_grade="B",
            description="기둥 표면 수평 균열",
            cause="건조 수축에 의한 미세 균열",
        )
        for _ in range(n)
    ]
    return ReportDetail(items=items)


def _sample_recommendation(legal_basis: str = "콘크리트 구조 설계기준 제X조") -> ReportRecommendation:
    return ReportRecommendation(
        items=[
            RecommendationItem(
                target="균열", method="에폭시 수지 주입 공법", priority="중", legal_basis=legal_basis
            )
        ],
        monitoring_points=["지하주차장 균열 발생 부위"],
    )


# ── 프롬프트 조립 단위 테스트 ──


def test_full_grade_counts_fills_all_grades_with_zero():
    counts = full_grade_counts(_sample_defects())
    assert counts == {"A": 0, "B": 1, "C": 0, "D": 0, "E": 0}


def test_build_prompt_overview_includes_facility_info():
    prompt = _build_prompt_overview(_sample_facility_info())
    assert "Haja APT" in prompt
    assert "서울시" in prompt


def test_build_prompt_summary_injects_precomputed_counts_not_llm_computed():
    prompt = _build_prompt_summary(_sample_defects())
    assert "총 하자 건수: 1건" in prompt
    assert "B등급 1건" in prompt


def test_build_prompt_detail_includes_defect_count_and_list():
    prompt = _build_prompt_detail(_sample_defects())
    assert "1건" in prompt
    assert "균열" in prompt
    assert "1동 1층 기둥" in prompt


def test_build_prompt_recommendation_uses_no_result_notice_when_context_empty():
    prompt = _build_prompt_recommendation(_sample_defects(), "")
    assert "검색 결과 없음" in prompt


def test_report_summary_requires_count_by_grade_and_key_findings():
    """P2 픽스: count_by_grade/key_findings는 기본값이 없어야 한다 — LLM이 필드를 누락하면
    structured output 파싱 단계(PydanticOutputParser)에서 조용히 빈 dict/list로 통과하지 않고
    ValidationError로 실패해 기존 재시도 경로를 타야 한다(grounding이 빈 dict를 0건으로 오판 방지)."""
    with pytest.raises(ValidationError):
        ReportSummary(overall_opinion="양호", total_count=1)


# ── run_report_chain / /ai/report e2e (LLM + vectorstore 모킹) ──


def _patch_all_sections(mock_get_llm, overview=None, summary=None, detail=None, recommendation=None):
    """4개 섹션 체인이 모두 같은 get_llm()을 호출하므로, 스키마별로 다른 반환값을 주기 위해
    with_structured_output(schema) 호출 시 schema에 따라 분기하는 MagicMock을 구성한다.
    """
    overview = overview or _sample_overview()
    summary = summary or _sample_summary()
    detail = detail or _sample_detail()
    recommendation = recommendation or _sample_recommendation()

    outputs = {
        ReportOverview: overview,
        ReportSummary: summary,
        ReportDetail: detail,
        ReportRecommendation: recommendation,
    }

    def _with_structured_output(schema):
        structured = MagicMock()
        # summary는 재생성 흐름에서 여러 번 호출될 수 있으므로 매 invoke마다 최신 값을 반환
        structured.invoke.side_effect = lambda *_a, **_kw: outputs[schema]
        return structured

    mock_llm = MagicMock()
    mock_llm.with_structured_output.side_effect = _with_structured_output
    mock_get_llm.return_value = mock_llm
    return outputs


@patch("ai.chains.report_chain.get_vectorstore")
@patch("ai.chains.report_chain.get_llm")
def test_run_report_chain_success_matches_contract_field_names(mock_get_llm, mock_get_vectorstore):
    mock_get_vectorstore.side_effect = NotImplementedError("stub")
    _patch_all_sections(mock_get_llm)

    result = run_report_chain(_sample_facility_info(), _sample_defects(), on_mismatch="regenerate")

    assert set(result.keys()) == {"overview", "summary", "detail", "recommendation", "grounding_ok"}
    assert result["overview"]["purpose"]
    assert result["summary"]["total_count"] == 1
    assert result["summary"]["count_by_grade"] == {"A": 0, "B": 1, "C": 0, "D": 0, "E": 0}
    assert len(result["detail"]["items"]) == 1
    assert result["detail"]["items"][0]["defect_type"] == "균열"
    assert len(result["recommendation"]["items"]) == 1
    assert result["grounding_ok"] is True


@patch("ai.chains.report_chain.get_vectorstore")
@patch("ai.chains.report_chain.get_llm")
def test_report_endpoint_success_envelope(mock_get_llm, mock_get_vectorstore):
    mock_get_vectorstore.side_effect = NotImplementedError("stub")
    _patch_all_sections(mock_get_llm)

    res = client.post(
        "/ai/report",
        json={
            "facility_info": _sample_facility_info(),
            "confirmed_defects": _sample_defects(),
            "on_mismatch": "regenerate",
        },
    )
    assert res.status_code == 200
    body = res.json()
    assert body["success"] is True
    assert body["data"]["grounding_ok"] is True
    assert body["data"]["summary"]["total_count"] == 1
    assert body["data"]["detail"]["items"][0]["cause"] == "건조 수축에 의한 미세 균열"
    assert body["data"]["recommendation"]["monitoring_points"] == ["지하주차장 균열 발생 부위"]


@patch("ai.chains.report_chain.get_llm")
def test_report_endpoint_llm_failure_returns_error_envelope(mock_get_llm):
    mock_get_llm.side_effect = KeyError("HF_API_TOKEN")

    res = client.post(
        "/ai/report",
        json={"facility_info": _sample_facility_info(), "confirmed_defects": _sample_defects()},
    )
    assert res.status_code == 200
    body = res.json()
    assert body["success"] is False
    assert body["error"]["code"] == "LLM_INVALID_OUTPUT"


@patch("ai.chains.report_chain.get_vectorstore")
@patch("ai.chains.report_chain.get_llm")
def test_report_endpoint_output_parser_exception_returns_llm_invalid_output_not_validation_error(
    mock_get_llm, mock_get_vectorstore
):
    """P1 회귀 방지: OutputParserException은 ValueError의 서브클래스이므로, 라우터의
    (ValueError, PydanticValidationError) 절보다 먼저 OutputParserException을 잡아야 한다.
    이 케이스는 _StructuredLLM.invoke()가 MAX_RETRIES 소진 후 던지는 "진짜 LLM 출력 파싱 실패"
    (contract.md 기준 LLM_INVALID_OUTPUT)이므로, VALIDATION_ERROR로 오분류되면 안 된다."""
    mock_get_vectorstore.side_effect = NotImplementedError("stub")
    mock_llm = MagicMock()
    mock_llm.with_structured_output.return_value.invoke.side_effect = OutputParserException(
        "malformed/incomplete JSON — LLM 출력 파싱 실패"
    )
    mock_get_llm.return_value = mock_llm

    res = client.post(
        "/ai/report",
        json={"facility_info": _sample_facility_info(), "confirmed_defects": _sample_defects()},
    )
    assert res.status_code == 200
    body = res.json()
    assert body["success"] is False
    assert body["error"]["code"] == "LLM_INVALID_OUTPUT"


@patch("ai.chains.report_chain.get_vectorstore")
@patch("ai.chains.report_chain.get_llm")
def test_grounding_mismatch_triggers_regenerate_then_recovers(mock_get_llm, mock_get_vectorstore):
    """summary가 처음엔 실측(1건)과 다른 5건을 주장 → REGENERATE 재시도 → 재생성된 값이 일치하면 grounding_ok True."""
    mock_get_vectorstore.side_effect = NotImplementedError("stub")
    wrong_summary = _sample_summary(total_count=5)
    wrong_summary.count_by_grade = {"A": 0, "B": 5, "C": 0, "D": 0, "E": 0}
    correct_summary = _sample_summary(total_count=1)

    outputs = {
        ReportOverview: _sample_overview(),
        ReportDetail: _sample_detail(),
        ReportRecommendation: _sample_recommendation(),
    }
    summary_calls = {"n": 0}

    def _with_structured_output(schema):
        structured = MagicMock()
        if schema is ReportSummary:
            def _invoke(*_a, **_kw):
                summary_calls["n"] += 1
                return wrong_summary if summary_calls["n"] == 1 else correct_summary
            structured.invoke.side_effect = _invoke
        else:
            structured.invoke.side_effect = lambda *_a, **_kw: outputs[schema]
        return structured

    mock_llm = MagicMock()
    mock_llm.with_structured_output.side_effect = _with_structured_output
    mock_get_llm.return_value = mock_llm

    result = run_report_chain(_sample_facility_info(), _sample_defects(), on_mismatch="regenerate")

    assert summary_calls["n"] >= 2  # 최초 생성 + 재생성 최소 1회
    assert result["grounding_ok"] is True
    assert result["summary"]["total_count"] == 1


@patch("ai.chains.report_chain.get_vectorstore")
@patch("ai.chains.report_chain.get_llm")
def test_grounding_mismatch_persists_sets_grounding_ok_false_but_still_returns_report(
    mock_get_llm, mock_get_vectorstore
):
    """재생성해도 계속 불일치하면 WARN 성격으로 전환 — grounding_ok=False 이지만 보고서 생성 자체는 실패시키지 않는다."""
    mock_get_vectorstore.side_effect = NotImplementedError("stub")
    always_wrong_summary = _sample_summary(total_count=5)
    always_wrong_summary.count_by_grade = {"A": 0, "B": 5, "C": 0, "D": 0, "E": 0}
    _patch_all_sections(mock_get_llm, summary=always_wrong_summary)

    result = run_report_chain(_sample_facility_info(), _sample_defects(), on_mismatch="regenerate")

    assert result["grounding_ok"] is False
    assert result["summary"]["total_count"] == 5  # 보고서는 그대로 반환(생성 자체를 막지 않음)


@patch("ai.chains.report_chain.get_vectorstore")
@patch("ai.chains.report_chain.get_llm")
def test_regenerate_loop_output_parser_exception_propagates_as_llm_invalid_output(
    mock_get_llm, mock_get_vectorstore
):
    """재생성(regenerate) 루프에서 _run_summary_chain 재호출이 OutputParserException을 던지는 경우도
    같은 원인(라우터의 except 순서)으로 오분류될 수 있었다 — run_report_chain은 이 예외를 삼키지 않고
    그대로 위로 전파해야 하며, 라우터 픽스가 이 경로에도 적용되는지 e2e로 확인한다."""
    mock_get_vectorstore.side_effect = NotImplementedError("stub")
    wrong_summary = _sample_summary(total_count=5)
    wrong_summary.count_by_grade = {"A": 0, "B": 5, "C": 0, "D": 0, "E": 0}

    outputs = {
        ReportOverview: _sample_overview(),
        ReportDetail: _sample_detail(),
        ReportRecommendation: _sample_recommendation(),
    }
    summary_calls = {"n": 0}

    def _with_structured_output(schema):
        structured = MagicMock()
        if schema is ReportSummary:
            def _invoke(*_a, **_kw):
                summary_calls["n"] += 1
                if summary_calls["n"] == 1:
                    return wrong_summary  # 최초 생성 — grounding 불일치로 REGENERATE 트리거
                raise OutputParserException("재생성 시도에서도 malformed JSON")
            structured.invoke.side_effect = _invoke
        else:
            structured.invoke.side_effect = lambda *_a, **_kw: outputs[schema]
        return structured

    mock_llm = MagicMock()
    mock_llm.with_structured_output.side_effect = _with_structured_output
    mock_get_llm.return_value = mock_llm

    res = client.post(
        "/ai/report",
        json={"facility_info": _sample_facility_info(), "confirmed_defects": _sample_defects()},
    )
    assert res.status_code == 200
    body = res.json()
    assert body["success"] is False
    assert body["error"]["code"] == "LLM_INVALID_OUTPUT"


@patch("ai.chains.report_chain.get_vectorstore")
@patch("ai.chains.report_chain.get_llm")
def test_detail_item_count_mismatch_returns_validation_error(mock_get_llm, mock_get_vectorstore):
    """detail.items 개수가 confirmed_defects와 다르면 report_chain에서 직접 검증해 실패 처리(design §5-4).

    P3 픽스: 이 실패는 LLM 호출·파싱 실패가 아니라 report_chain 자체의 비-LLM 검증(ValueError)이므로
    /ai/grounding-check와 동일하게 VALIDATION_ERROR로 분리되어야 한다(LLM_INVALID_OUTPUT과 뭉뚱그리지 않음).
    """
    mock_get_vectorstore.side_effect = NotImplementedError("stub")
    _patch_all_sections(mock_get_llm, detail=_sample_detail(n=2))  # 입력은 1건인데 출력은 2건

    res = client.post(
        "/ai/report",
        json={"facility_info": _sample_facility_info(), "confirmed_defects": _sample_defects()},
    )
    assert res.status_code == 200
    body = res.json()
    assert body["success"] is False
    assert body["error"]["code"] == "VALIDATION_ERROR"


@patch("ai.chains.report_chain.get_vectorstore")
@patch("ai.chains.report_chain.get_llm")
def test_invalid_severity_grade_returns_validation_error(mock_get_llm, mock_get_vectorstore):
    """confirmed_defects의 severity_grade가 A~E 밖이면 GroundingDefect validator가 실패한다(P3 픽스).

    이 역시 LLM과 무관한 입력 데이터 무결성 오류이므로 LLM_INVALID_OUTPUT이 아니라 VALIDATION_ERROR여야 한다.
    """
    mock_get_vectorstore.side_effect = NotImplementedError("stub")
    _patch_all_sections(mock_get_llm)
    bad_defects = [{**_sample_defects()[0], "severity_grade": "Z"}]

    res = client.post(
        "/ai/report",
        json={"facility_info": _sample_facility_info(), "confirmed_defects": bad_defects},
    )
    assert res.status_code == 200
    body = res.json()
    assert body["success"] is False
    assert body["error"]["code"] == "VALIDATION_ERROR"


def test_report_endpoint_confirmed_defects_missing_required_field_returns_422():
    """P2 픽스: confirmed_defects 배열 원소가 ConfirmedDefectInput(Pydantic)으로 검증된다 —
    defect_type 등 필수 필드가 없으면 LLM 호출 전에 요청 단계(422)에서 거부되어야 한다
    (기존엔 list[dict]라 아무 필드가 빠져도 report_chain 내부에서 d.get(key, '-')로 조용히 통과됨)."""
    res = client.post(
        "/ai/report",
        json={
            "facility_info": _sample_facility_info(),
            "confirmed_defects": [{"location": "1동 1층 기둥", "severity_grade": "B", "description": "균열"}],
        },
    )
    assert res.status_code == 422


@patch("ai.chains.report_chain.get_vectorstore")
@patch("ai.chains.report_chain.get_llm")
def test_legal_basis_verified_true_when_citation_found_in_partial_rag_context(
    mock_get_llm, mock_get_vectorstore
):
    """P2 픽스: RAG 검색 결과가 있고(부분) LLM의 legal_basis 인용이 그 컨텍스트에 실제로 포함되면
    legal_basis_verified=True — 기존엔 검색 결과가 0건일 때만 검증했다."""
    mock_doc = MagicMock()
    mock_doc.page_content = "공동주택관리법 제33조 안전점검"
    mock_get_vectorstore.return_value.similarity_search.return_value = [mock_doc]
    recommendation = _sample_recommendation(legal_basis="공동주택관리법 제33조 안전점검")
    _patch_all_sections(mock_get_llm, recommendation=recommendation)

    result = run_report_chain(_sample_facility_info(), _sample_defects(), on_mismatch="regenerate")

    item = result["recommendation"]["items"][0]
    assert item["legal_basis"] == "공동주택관리법 제33조 안전점검"  # 검색 결과 있을 땐 강제 대체 안 함
    assert item["legal_basis_verified"] is True


@patch("ai.chains.report_chain.get_vectorstore")
@patch("ai.chains.report_chain.get_llm")
def test_legal_basis_verified_false_when_citation_not_in_partial_rag_context(
    mock_get_llm, mock_get_vectorstore
):
    """P2 픽스: 검색 결과는 있지만 LLM이 그와 무관하거나 지어낸 조문을 인용하면
    legal_basis는 그대로 두되(오탐으로 내용을 훼손하지 않음) legal_basis_verified=False로 신호를 남긴다
    (기존엔 이 경로에서 아무 검증도 없이 LLM 인용을 그대로 신뢰했음 — PR머신 P2 지적)."""
    mock_doc = MagicMock()
    mock_doc.page_content = "공동주택관리법 제33조 안전점검"
    mock_get_vectorstore.return_value.similarity_search.return_value = [mock_doc]
    hallucinated = _sample_recommendation(legal_basis="건축법 제99조(가상 조문)")
    _patch_all_sections(mock_get_llm, recommendation=hallucinated)

    result = run_report_chain(_sample_facility_info(), _sample_defects(), on_mismatch="regenerate")

    item = result["recommendation"]["items"][0]
    assert item["legal_basis"] == "건축법 제99조(가상 조문)"
    assert item["legal_basis_verified"] is False


@patch("ai.chains.report_chain.get_vectorstore")
@patch("ai.chains.report_chain.get_llm")
def test_vectorstore_not_implemented_falls_back_to_no_basis_notice(mock_get_llm, mock_get_vectorstore):
    """vectorstore.py가 아직 NotImplementedError 스텁이어도 recommendation은 legal_basis를
    "관련 근거 없음"으로 강제 대체되어 정상 응답한다(요청 전체를 실패시키지 않음)."""
    mock_get_vectorstore.side_effect = NotImplementedError("온보딩 세션(7/15) 전 AI-LLM 코치가 구현")
    # LLM이 그럴듯한 법규를 만들어냈다고 가정해도, 코드가 강제로 "관련 근거 없음"으로 덮어써야 함
    hallucinated_recommendation = _sample_recommendation(legal_basis="콘크리트 구조 설계기준 제99조(가상 조문)")
    _patch_all_sections(mock_get_llm, recommendation=hallucinated_recommendation)

    result = run_report_chain(_sample_facility_info(), _sample_defects(), on_mismatch="regenerate")

    assert mock_get_vectorstore.called
    for item in result["recommendation"]["items"]:
        assert item["legal_basis"] == "관련 근거 없음"


if __name__ == "__main__":
    test_full_grade_counts_fills_all_grades_with_zero()
    test_build_prompt_overview_includes_facility_info()
    test_build_prompt_summary_injects_precomputed_counts_not_llm_computed()
    test_build_prompt_detail_includes_defect_count_and_list()
    test_build_prompt_recommendation_uses_no_result_notice_when_context_empty()
    print("OK: report chain/endpoint self-check passed (see pytest for full e2e coverage)")
