"""대시보드 AI 주간 브리핑 체인/엔드포인트 검증 (실제 HF/Redis 없이 get_llm만 모킹).

- 파생 사실(전주 대비 변화율·추세)이 코드로 정확히 계산되는지 (LLM 무관)
- 프롬프트 조립이 현황 수치를 담는지
- /ai/briefing 이 structured output + 계산 facts를 AIResponse로 감싸는지
- LLM 예외 시 서버가 죽지 않고 AIResponse.fail 로 응답하는지
"""
from unittest.mock import MagicMock, patch

from fastapi.testclient import TestClient

from ai.chains.briefing_chain import (
    DashboardStats,
    WeeklyBriefing,
    _build_prompt,
    derive_facts,
)
from main import app

client = TestClient(app)


def _sample() -> DashboardStats:
    # 이번 주 45건 / 지난 주 51건 → 12% 감소 (스크린샷과 동일)
    return DashboardStats(
        total_facilities=24, monthly_analysis=1284, pending_review=37, pending_action=12,
        this_week_defects=45, last_week_defects=51, top_defect_type="균열", critical_defects=3,
    )


def test_derive_facts_decrease():
    facts = derive_facts(_sample())
    assert facts.trend == "감소"
    assert facts.change_pct == 12  # round(6/51*100)


def test_derive_facts_increase_and_zero_baseline():
    up = derive_facts(DashboardStats(
        total_facilities=1, monthly_analysis=1, pending_review=0, pending_action=0,
        this_week_defects=10, last_week_defects=8, top_defect_type="누수", critical_defects=0))
    assert up.trend == "증가" and up.change_pct == 25
    zero = derive_facts(DashboardStats(
        total_facilities=1, monthly_analysis=1, pending_review=0, pending_action=0,
        this_week_defects=5, last_week_defects=0, top_defect_type="박리", critical_defects=1))
    assert zero.change_pct is None and zero.trend == "증가"


def test_build_prompt_includes_stats():
    prompt = _build_prompt(_sample(), derive_facts(_sample()))
    assert "45" in prompt and "균열" in prompt
    assert "12% 감소" in prompt


@patch("ai.chains.briefing_chain.get_llm")
def test_briefing_endpoint_success(mock_get_llm):
    mock_llm = MagicMock()
    mock_llm.with_structured_output.return_value.invoke.return_value = WeeklyBriefing(
        briefing="이번 주 하자는 총 45건으로 전주 대비 12% 감소했습니다. 주요 유형은 균열입니다.",
        recommendation="D등급 이상 3건에 대한 즉각 조치를 권장합니다.",
    )
    mock_get_llm.return_value = mock_llm

    res = client.post("/ai/briefing", json=_sample().model_dump())
    assert res.status_code == 200
    body = res.json()
    assert body["success"] is True
    assert "45건" in body["data"]["briefing"]
    assert body["data"]["facts"]["change_pct"] == 12
    assert body["data"]["facts"]["trend"] == "감소"


@patch("ai.chains.briefing_chain.get_llm")
def test_briefing_endpoint_llm_failure_returns_error_envelope(mock_get_llm):
    mock_get_llm.side_effect = KeyError("HF_API_TOKEN")

    res = client.post("/ai/briefing", json=_sample().model_dump())
    assert res.status_code == 200
    body = res.json()
    assert body["success"] is False
    assert body["error"]["code"] == "LLM_INVALID_OUTPUT"


if __name__ == "__main__":
    test_derive_facts_decrease()
    test_derive_facts_increase_and_zero_baseline()
    test_build_prompt_includes_stats()
    test_briefing_endpoint_success()
    test_briefing_endpoint_llm_failure_returns_error_envelope()
    print("OK: briefing chain/endpoint self-check passed")
