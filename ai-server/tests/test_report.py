"""report_chain / /ai/report 엔드포인트 검증 (실제 HF/Redis/Chroma 호출 없이 get_llm·get_vectorstore만 모킹).

- 4개 섹션 프롬프트 조립이 실제 prompts/report_*.md 파일을 읽어 정상 조립되는지
- /ai/report 이 4섹션 + grounding_ok 를 contract.md 필드명 그대로 AIResponse envelope으로 감싸는지
- LLM 예외 시 서버가 죽지 않고 AIResponse.fail 로 응답하는지
- Grounding 불일치 시 REGENERATE 재시도 흐름이 동작하는지
- detail 섹션 items 개수가 confirmed_defects와 다르면 실패 처리되는지
- vectorstore.get_vectorstore()가 NotImplementedError를 던져도 recommendation이 "관련 근거 없음"으로 폴백되는지
"""
from unittest.mock import MagicMock, patch

from fastapi.testclient import TestClient

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
def test_detail_item_count_mismatch_raises(mock_get_llm, mock_get_vectorstore):
    """detail.items 개수가 confirmed_defects와 다르면 report_chain에서 직접 검증해 실패 처리(design §5-4)."""
    mock_get_vectorstore.side_effect = NotImplementedError("stub")
    _patch_all_sections(mock_get_llm, detail=_sample_detail(n=2))  # 입력은 1건인데 출력은 2건

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
