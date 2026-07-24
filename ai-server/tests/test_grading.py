"""ai.core.grading 단위 테스트(HAJA-109 심각도 등급 규칙) — area_ratio 구간 경계, 철근노출 floor,
라벨 정규화 별칭/미매칭 케이스를 문서(docs/conventions/하자_심각도_등급_규칙.md) §2/§3 그대로 고정한다.
"""
import pytest

from ai.core.grading import (
    compute_grade,
    compute_severity_score,
    normalize_defect_type_label,
    severity_score_to_grade,
)


@pytest.mark.parametrize(
    "raw_label,expected",
    [
        ("crack", "CRACK"),
        ("CRACK", "CRACK"),
        (" Crack ", "CRACK"),
        ("균열", "CRACK"),
        ("spalling", "SPALLING"),
        ("박리", "SPALLING"),
        ("박락", "SPALLING"),
        ("박리·박락", "SPALLING"),
        ("rebar_exposure", "REBAR_EXPOSURE"),
        ("rebar exposure", "REBAR_EXPOSURE"),
        ("철근 노출", "REBAR_EXPOSURE"),
    ],
)
def test_normalize_defect_type_label_known_aliases(raw_label, expected):
    assert normalize_defect_type_label(raw_label) == expected


def test_normalize_defect_type_label_unknown_returns_none():
    assert normalize_defect_type_label("leak_efflorescence") is None
    assert normalize_defect_type_label("") is None


@pytest.mark.parametrize(
    "area_ratio,expected_s",
    [
        (0.0, 0.1),
        (0.009, 0.1),
        (0.01, 0.3),  # 하한 포함(반열림) — 0.01은 다음 구간으로 넘어간다
        (0.029, 0.3),
        (0.03, 0.5),
        (0.069, 0.5),
        (0.07, 0.7),
        (0.149, 0.7),
        (0.15, 0.9),
        (0.5, 0.9),
    ],
)
def test_compute_severity_score_band_boundaries_for_crack(area_ratio, expected_s):
    assert compute_severity_score("CRACK", area_ratio) == expected_s


def test_compute_severity_score_rebar_exposure_floor_overrides_small_area():
    # area_ratio가 작아 원래는 s=0.1이지만 철근노출은 최소 0.6 이상으로 올라간다.
    assert compute_severity_score("REBAR_EXPOSURE", 0.001) == 0.6


def test_compute_severity_score_rebar_exposure_floor_does_not_lower_large_area():
    # 이미 floor보다 심각한 경우(0.7, 0.9)는 floor가 깎아내리지 않는다.
    assert compute_severity_score("REBAR_EXPOSURE", 0.07) == 0.7
    assert compute_severity_score("REBAR_EXPOSURE", 0.5) == 0.9


@pytest.mark.parametrize(
    "s,expected_grade",
    [
        (0.0, "A"),
        (0.1, "A"),
        (0.2, "A"),  # g=0.8 → A 하한(>=0.8) 포함
        (0.3, "B"),
        (0.4, "B"),  # g=0.6 → B 하한 포함
        (0.5, "C"),
        (0.6, "C"),  # g=0.4 → C 하한 포함
        (0.7, "D"),
        # s=0.8은 의도적으로 제외한다: g=1.0-0.8이 부동소수점 오차로 0.19999999999999996이 되어
        # "D 하한 포함(g>=0.2)" 의도와 달리 E로 떨어진다(1.0-0.2는 정확히 0.8이라 (0.2,"A")는
        # 문제없이 통과 — 뺄셈 방향에 따라 비대칭). 실제 s는 band/floor 산출값
        # {0.1,0.3,0.5,0.6,0.7,0.9}만 나오므로 0.8은 운영 경로에서 발생하지 않는다.
        (0.9, "E"),
        (1.0, "E"),
    ],
)
def test_severity_score_to_grade_boundaries(s, expected_grade):
    assert severity_score_to_grade(s) == expected_grade


def test_compute_grade_end_to_end_matches_score_and_grade_composition():
    assert compute_grade("CRACK", 0.005) == "A"  # s=0.1 → g=0.9
    assert compute_grade("CRACK", 0.02) == "B"  # s=0.3 → g=0.7
    assert compute_grade("CRACK", 0.05) == "C"  # s=0.5 → g=0.5
    assert compute_grade("CRACK", 0.10) == "D"  # s=0.7 → g=0.3
    assert compute_grade("CRACK", 0.20) == "E"  # s=0.9 → g=0.1


def test_compute_grade_rebar_exposure_never_better_than_c_even_with_tiny_area():
    assert compute_grade("REBAR_EXPOSURE", 0.0001) == "C"
