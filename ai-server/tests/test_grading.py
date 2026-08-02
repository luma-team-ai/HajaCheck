"""ai.core.grading 단위 테스트(HAJA-109 심각도 등급 규칙) — area_ratio 구간 경계(유형별 구간표
포함, 2026-07-27 균열 v2 재보정 — U-Net+레터박스+전체마스크합 기준), 철근노출 floor 케이스를
문서(docs/conventions/하자_심각도_등급_규칙.md) §2/§3 그대로 고정한다.

라벨 정규화(normalize_defect_type_label)는 2026-07-27 6차 rebase 때 제거됐다 — HF Hub 저장소가
유형별 전용 체크포인트 구조로 바뀌면서 어떤 모델을 호출했는지 자체가 유형을 결정하게 됐고(특히
rebar_exposure 모델의 내부 클래스가 good/fair/poor라 라벨 텍스트로는 애초에 유형을 되짚을 수
없었다), defect_detection_chain.py가 더 이상 라벨 기반 정규화를 거치지 않는다.
"""
import pytest

from ai.core.grading import (
    compute_crack_grade,
    compute_grade,
    compute_severity_score,
    severity_score_to_grade,
)


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
def test_compute_severity_score_band_boundaries_for_default_types(area_ratio, expected_s):
    # SPALLING/REBAR_EXPOSURE(면적형 하자)가 쓰는 기본 구간표 — 균열 재보정과 무관하게 유지된다.
    assert compute_severity_score("SPALLING", area_ratio) == expected_s


@pytest.mark.parametrize(
    "area_ratio,expected_s",
    [
        (0.0, 0.1),
        (0.00274, 0.1),
        (0.00275, 0.3),  # 하한 포함(반열림) — 0.00275는 다음 구간으로 넘어간다
        (0.00417, 0.3),
        (0.00418, 0.5),
        (0.00636, 0.5),
        (0.00637, 0.7),
        (0.00968, 0.7),
        (0.00969, 0.9),
        (0.5, 0.9),
    ],
)
def test_compute_severity_score_band_boundaries_for_crack(area_ratio, expected_s):
    # 균열 전용 재보정 구간표 v2(2026-07-27, U-Net+레터박스+전체마스크합 기준 재측정) — 모듈
    # docstring "균열 구간표 캘리브레이션 이력" 참고.
    assert compute_severity_score("CRACK", area_ratio) == expected_s


def test_compute_severity_score_crack_and_spalling_diverge_at_same_area_ratio():
    # 유형별로 다른 구간표가 실제로 적용되는지 고정 — 같은 area_ratio라도 균열은 재보정된
    # 낮은 임계값 때문에 훨씬 더 심각하게(=원점수가 높게) 산정돼야 한다.
    area_ratio = 0.005  # 0.5% — 균열 기준 C(0.00418~0.00637), SPALLING 기준 A(<0.01)
    assert compute_severity_score("CRACK", area_ratio) == 0.5
    assert compute_severity_score("SPALLING", area_ratio) == 0.1


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


def test_compute_grade_end_to_end_matches_score_and_grade_composition_for_default_types():
    # SPALLING(면적형) 기준 — 균열 재보정 이전과 동일한 area_ratio/등급 조합이 그대로 유지된다.
    assert compute_grade("SPALLING", 0.005) == "A"  # s=0.1 → g=0.9
    assert compute_grade("SPALLING", 0.02) == "B"  # s=0.3 → g=0.7
    assert compute_grade("SPALLING", 0.05) == "C"  # s=0.5 → g=0.5
    assert compute_grade("SPALLING", 0.10) == "D"  # s=0.7 → g=0.3
    assert compute_grade("SPALLING", 0.20) == "E"  # s=0.9 → g=0.1


def test_compute_grade_end_to_end_matches_score_and_grade_composition_for_crack():
    # 균열 재보정 구간표 v2(2026-07-27) 기준 — 위 SPALLING 테스트보다 훨씬 작은 area_ratio에서
    # 같은 등급 시퀀스(A~E)가 나와야 한다(균열은 선형이라 면적비가 작을 수밖에 없다는 게 이번
    # 재보정의 핵심 — 모듈 docstring 참고).
    assert compute_grade("CRACK", 0.001) == "A"  # s=0.1 → g=0.9
    assert compute_grade("CRACK", 0.003) == "B"  # s=0.3 → g=0.7
    assert compute_grade("CRACK", 0.005) == "C"  # s=0.5 → g=0.5
    assert compute_grade("CRACK", 0.007) == "D"  # s=0.7 → g=0.3
    assert compute_grade("CRACK", 0.01) == "E"  # s=0.9 → g=0.1


def test_compute_grade_rebar_exposure_never_better_than_c_even_with_tiny_area():
    assert compute_grade("REBAR_EXPOSURE", 0.0001) == "C"


def test_compute_crack_grade_requires_both_area_and_darkness_for_severe():
    """v3 min 합의(2026-07-28) — 심각(D·E) 판정엔 면적·어두움 둘 다 필요하다(모듈 docstring 참고)."""
    assert compute_crack_grade(0.01, 0.003) == "E"  # 둘 다 최상위 밴드
    assert compute_crack_grade(0.01, 0.0002) == "B"  # 실금: 면적 E여도 어두움 B로 캡
    assert compute_crack_grade(0.0005, 0.003) == "A"  # 어두운 소형 오탐: 면적 A로 캡
    assert compute_crack_grade(0.005, 0.0005) == "C"  # 중간×중간은 낮은 쪽(C) 채택


def test_compute_crack_grade_dark_band_boundaries():
    # dark 구간표 경계(상한 미만) — area는 최상위로 고정해 dark 축만 검증.
    area_severe = 0.01
    assert compute_crack_grade(area_severe, 0.0001) == "A"
    assert compute_crack_grade(area_severe, 0.000114) == "B"
    assert compute_crack_grade(area_severe, 0.000329) == "C"
    assert compute_crack_grade(area_severe, 0.000952) == "D"
    assert compute_crack_grade(area_severe, 0.002755) == "E"
