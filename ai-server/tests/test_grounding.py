"""Grounding Check (사실 검증) 로직/엔드포인트 검증 — LLM 호출 없는 결정론적 대조 (HAJA-117).

- 실측 defects 집계(summarize_defects)
- 주장 수치·등급·유형 일치/불일치 판정(check_grounding) + 조치(PASS/REGENERATE/WARN)
- /ai/grounding-check 이 공통 AIResponse envelope으로 감싸는지 (성공/불일치/예외 폴백)
"""
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from ai.core.grounding import (
    CheckStatus,
    GroundingAction,
    GroundingClaims,
    GroundingDefect,
    MismatchPolicy,
    check_grounding,
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


def test_check_grounding_type_mismatch():
    # 유형별 대조: 실측 균열 2건인데 5건이라 주장 → 불일치
    claims = GroundingClaims(count_by_type={"균열": 5})
    result = check_grounding(_sample_defects(), claims)
    assert result.grounded is False
    assert result.mismatches[0].field == "type:균열"
    assert result.mismatches[0].actual == "2"


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


def test_grade_validator_normalizes():
    # 'c등급'·' d ' 등 오탈자성 표기를 A~E 대문자로 정규화
    assert GroundingDefect(defect_type="균열", grade="c등급").grade == "C"
    assert GroundingDefect(defect_type="박리", grade=" d ").grade == "D"


def test_grade_validator_rejects_invalid():
    # A~E가 아닌 등급은 경계에서 예외 — 매칭 키 오염 방지 (P2)
    with pytest.raises(ValidationError):
        GroundingDefect(defect_type="균열", grade="X")
    with pytest.raises(ValidationError):
        GroundingDefect(defect_type="균열", grade="")


def test_check_grounding_empty_defects_default_claims_passes():
    # 실측 0건 + 기본값 claims(주장 없음) → 대조 항목 없음, 통과 (경계 조건)
    result = check_grounding([], GroundingClaims())
    assert result.grounded is True
    assert result.action is GroundingAction.PASS
    assert result.checks == []
    assert result.mismatches == []
    assert result.ground_truth.total_count == 0
    assert result.ground_truth.count_by_grade == {}
    assert result.ground_truth.count_by_type == {}


def test_check_grounding_zero_total_count_matches():
    # 실측 0건인데 생성물도 0건 주장 → 일치, 통과
    result = check_grounding([], GroundingClaims(total_count=0))
    assert result.grounded is True
    assert result.action is GroundingAction.PASS
    assert len(result.checks) == 1
    assert result.checks[0].status is CheckStatus.MATCH


def test_check_grounding_empty_defects_positive_claim_is_unverifiable():
    # 실측 0건(대조 근거 없음)인데 생성물이 3건·C3 주장 → 환각으로 단정하지 않고
    # 검증 불가(UNVERIFIABLE)로 분기, 재생성 대신 사람 확인(WARN). (#117)
    result = check_grounding([], GroundingClaims(total_count=3, count_by_grade={"C": 3}))
    assert result.grounded is True  # 확정 불일치(MISMATCH) 없음
    assert result.action is GroundingAction.WARN
    assert result.mismatches == []
    assert len(result.unverifiable) == 2  # total_count + grade:C 둘 다 검증 불가
    assert all(c.status is CheckStatus.UNVERIFIABLE for c in result.unverifiable)


def test_check_grounding_empty_defects_mentioned_grade_unverifiable():
    # 근거 없음 + 유효 등급 언급 → UNVERIFIABLE, 유효하지 않은 등급(F)은 근거 무관 MISMATCH
    result = check_grounding([], GroundingClaims(mentioned_grades=["C", "F"]))
    statuses = {c.field: c.status for c in result.checks}
    assert statuses["mentioned_grade:C"] is CheckStatus.UNVERIFIABLE
    assert statuses["mentioned_grade:F"] is CheckStatus.MISMATCH
    assert result.action is GroundingAction.REGENERATE  # F 환각이 있으므로 재생성


def test_check_grounding_nonempty_defects_still_mismatches():
    # 근거가 있으면(비어있지 않으면) 기존대로 환각은 MISMATCH/REGENERATE (회귀 보호)
    result = check_grounding(_sample_defects(), GroundingClaims(total_count=99))
    assert result.grounded is False
    assert result.action is GroundingAction.REGENERATE
    assert result.unverifiable == []
    assert result.mismatches[0].status is CheckStatus.MISMATCH
    assert all(c.status is CheckStatus.MISMATCH for c in result.mismatches)


def test_summarize_defects_normalizes_type_whitespace():
    # 공백/서식만 다른 동일 유형은 하나로 집계 (P3-a)
    defects = [
        GroundingDefect(defect_type="균열", grade="C"),
        GroundingDefect(defect_type=" 균열 ", grade="C"),
        GroundingDefect(defect_type="누수", grade="D"),
    ]
    truth = summarize_defects(defects)
    assert truth.count_by_type == {"균열": 2, "누수": 1}


def test_check_grounding_type_whitespace_matches_not_mismatch():
    # 실측 균열 2건, 주장 " 균열 ": 2 → 정규화 후 MATCH (공백 차이로 오탐 안 함) (P3-a)
    result = check_grounding(_sample_defects(), GroundingClaims(count_by_type={" 균열 ": 2}))
    assert result.grounded is True
    assert result.action is GroundingAction.PASS
    assert result.checks[0].field == "type:균열"
    assert result.checks[0].status is CheckStatus.MATCH


@patch("routers.ai_router.check_grounding", side_effect=RuntimeError("boom"))
def test_grounding_endpoint_error_returns_fail_envelope(_mock):
    # 대조 중 예외 발생 시 서버가 죽지 않고 fail envelope 로 응답
    res = client.post(
        "/ai/grounding-check",
        json={"defects": [{"defect_type": "균열", "grade": "C"}], "claims": {"total_count": 1}},
    )
    assert res.status_code == 200
    body = res.json()
    assert body["success"] is False
    # grounding 은 LLM 무관 코드 경로 → 범용 VALIDATION_ERROR (P3-b)
    assert body["error"]["code"] == "VALIDATION_ERROR"


if __name__ == "__main__":
    test_summarize_defects_counts()
    test_check_grounding_all_match_passes()
    test_check_grounding_count_mismatch_regenerates()
    test_check_grounding_type_mismatch()
    test_check_grounding_warn_policy()
    test_check_grounding_hallucinated_grade()
    test_grade_validator_normalizes()
    test_check_grounding_empty_defects_default_claims_passes()
    test_check_grounding_zero_total_count_matches()
    test_check_grounding_empty_defects_positive_claim_is_unverifiable()
    test_check_grounding_empty_defects_mentioned_grade_unverifiable()
    test_check_grounding_nonempty_defects_still_mismatches()
    test_summarize_defects_normalizes_type_whitespace()
    test_check_grounding_type_whitespace_matches_not_mismatch()
    test_grounding_endpoint_success()
    test_grounding_endpoint_mismatch_returns_regenerate()
    print("OK: grounding check self-check passed")
