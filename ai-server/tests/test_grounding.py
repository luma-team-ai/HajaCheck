"""Grounding Check (사실 검증) 로직/엔드포인트 검증 — LLM 호출 없는 결정론적 대조 (HAJA-117).

- 실측 defects 집계(summarize_defects)
- 주장 수치·등급 일치/불일치 판정(check_grounding) + 조치(PASS/REGENERATE/WARN)
- 서술형 텍스트 주장 추출(extract_claims_from_text)
- /ai/grounding-check 이 공통 AIResponse envelope으로 감싸는지
"""
from fastapi.testclient import TestClient

from ai.core.grounding import (
    CheckStatus,
    GroundingAction,
    GroundingClaims,
    GroundingDefect,
    MismatchPolicy,
    check_generated_report,
    check_grounding,
    extract_claims_from_text,
    summarize_defects,
)
from main import app

client = TestClient(app)


def _sample_defects() -> list[GroundingDefect]:
    # 총 4건: 균열 C 2건 + 박리 D 1건 + 누수 C 1건 → C 3건, D 1건
    return [
        GroundingDefect(defect_type="균열", grade="C"),
        GroundingDefect(defect_type="균열", grade="C"),
        GroundingDefect(defect_type="박리", grade="D"),
        GroundingDefect(defect_type="누수", grade="C"),
    ]


def test_summarize_defects_counts():
    truth = summarize_defects(_sample_defects())
    assert truth.total_count == 4
    assert truth.count_by_grade == {"C": 3, "D": 1}
    assert truth.count_by_type == {"균열": 2, "박리": 1, "누수": 1}


def test_check_grounding_all_match_passes():
    claims = GroundingClaims(total_count=4, count_by_grade={"C": 3, "D": 1}, mentioned_grades=["C", "D"])
    result = check_grounding(_sample_defects(), claims)
    assert result.grounded is True
    assert result.action is GroundingAction.PASS
    assert result.mismatches == []


def test_check_grounding_count_mismatch_regenerates():
    # 실측 4건인데 생성물이 5건이라 주장 → 수치 환각
    claims = GroundingClaims(total_count=5)
    result = check_grounding(_sample_defects(), claims)
    assert result.grounded is False
    assert result.action is GroundingAction.REGENERATE
    assert len(result.mismatches) == 1
    assert result.mismatches[0].field == "total_count"
    assert result.mismatches[0].status is CheckStatus.MISMATCH


def test_check_grounding_warn_policy():
    claims = GroundingClaims(count_by_grade={"C": 99})
    result = check_grounding(_sample_defects(), claims, on_mismatch=MismatchPolicy.WARN)
    assert result.grounded is False
    assert result.action is GroundingAction.WARN


def test_check_grounding_hallucinated_grade():
    # 실측에 없는 E등급을 언급 + 유효하지 않은 F등급 언급 → 둘 다 불일치
    claims = GroundingClaims(mentioned_grades=["E", "F"])
    result = check_grounding(_sample_defects(), claims)
    assert result.grounded is False
    assert len(result.mismatches) == 2


def test_extract_claims_from_text():
    text = "이번 점검에서 총 4건의 하자가 발견되었습니다. C등급 3건, D등급 1건입니다."
    claims = extract_claims_from_text(text)
    assert claims.total_count == 4
    assert claims.count_by_grade == {"C": 3, "D": 1}
    assert "C" in claims.mentioned_grades and "D" in claims.mentioned_grades


def test_check_generated_report_detects_number_hallucination():
    # 서술은 12건이라 하지만 실측은 4건
    text = "총 12건의 하자가 확인되었습니다."
    result = check_generated_report(_sample_defects(), text)
    assert result.grounded is False
    assert result.action is GroundingAction.REGENERATE


def test_grounding_endpoint_success():
    res = client.post(
        "/ai/grounding-check",
        json={
            "defects": [
                {"defect_type": "균열", "grade": "C"},
                {"defect_type": "누수", "grade": "C"},
            ],
            "claims": {"total_count": 2, "count_by_grade": {"C": 2}},
        },
    )
    assert res.status_code == 200
    body = res.json()
    assert body["success"] is True
    assert body["data"]["grounded"] is True
    assert body["data"]["action"] == "PASS"


def test_grounding_endpoint_mismatch_returns_regenerate():
    res = client.post(
        "/ai/grounding-check",
        json={
            "defects": [{"defect_type": "균열", "grade": "C"}],
            "claims": {"total_count": 7},
        },
    )
    assert res.status_code == 200
    body = res.json()
    assert body["success"] is True
    assert body["data"]["grounded"] is False
    assert body["data"]["action"] == "REGENERATE"
    assert body["data"]["mismatches"][0]["field"] == "total_count"


if __name__ == "__main__":
    test_summarize_defects_counts()
    test_check_grounding_all_match_passes()
    test_check_grounding_count_mismatch_regenerates()
    test_check_grounding_warn_policy()
    test_check_grounding_hallucinated_grade()
    test_extract_claims_from_text()
    test_check_generated_report_detects_number_hallucination()
    test_grounding_endpoint_success()
    test_grounding_endpoint_mismatch_returns_regenerate()
    print("OK: grounding check self-check passed")
