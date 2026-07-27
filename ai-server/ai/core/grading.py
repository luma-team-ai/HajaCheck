"""하자 심각도 등급(A~E) 산정 — docs/conventions/하자_심각도_등급_규칙.md(HAJA-109) §2/§3 그대로 구현.

산정 위치는 그 문서 §4가 권장한 대로 "FastAPI 탐지 후처리"다(defect_explain_chain이 이미
severity_grade를 입력으로 기대하는 구조가 전제). Spring은 이 값을 그대로 저장만 하고 재계산하지
않는다. 임계값은 1차 초안(§3.2 "튜닝 대상")이라 상수 모듈로 하드코딩 — 후속 dev-11-03(관리자
하자 유형·등급 기준 관리)에서 DB화 예정.

## 균열(CRACK) 구간표 캘리브레이션(2026-07-26)

area_ratio(하자 마스크 픽셀 / 이미지 전체 픽셀, `defect_detection_chain._mask_area_ratio`)는
박리박락·철근노출 같은 면적형 하자에는 맞지만, 균열은 선형이라 아무리 심각해도 이 값이 매우
작다. 기존엔 3유형이 같은 구간표(A/B 경계 1%)를 공유해 균열이 거의 항상 A로 뭉개졌다 —
AI Hub 아파트 균열 검증셋 470장 실측 결과 불량(정답) 170장 중 95장(56%)이 A, D·E는 470장
전체에서 0장이었다. 균열 전용 구간(아래 `_CRACK_AREA_RATIO_SEVERITY_BANDS`)은 이 실측
분포(불량 area_ratio 중앙값 0.876%)에 맞춰 재보정한 값이다. SPALLING/REBAR_EXPOSURE는
이번 실측 대상이 아니므로 기존 값을 그대로 유지한다.
"""
from __future__ import annotations

# 철근 노출은 "존재 자체가 고위험" — area_ratio와 무관하게 최소 s=0.6(등급 D 이하)에서 시작한다.
REBAR_EXPOSURE_SEVERITY_FLOOR = 0.6

# area_ratio → 심각도 원점수 s 구간 매핑(상한 미만, 마지막 구간만 이상) — SPALLING/REBAR_EXPOSURE
# 기본값. 규칙 문서 §3.2 표 그대로(이번 균열 재보정과 무관하게 유지).
_DEFAULT_AREA_RATIO_SEVERITY_BANDS: list[tuple[float, float]] = [
    (0.01, 0.1),
    (0.03, 0.3),
    (0.07, 0.5),
    (0.15, 0.7),
]

# 균열(CRACK) 전용 — AI Hub 아파트 균열 검증셋 470장 실측 캘리브레이션(2026-07-26).
# 보통→C, 불량→E를 앵커로 로그 등간격 산출(모듈 docstring 참고): 0.138 / 0.234 / 0.396 / 0.673 %.
#
# ⚠️ 프레이밍(촬영거리) 의존성 — 이 값은 "촬영 조건이 유사하다"는 전제 하의 추정치다. 같은 균열도
# 2배 가까이서 찍으면(마스크 중심 50% 크롭 후 확대 시뮬레이션) area_ratio가 중앙값 1.5~1.8배로
# 변해, 중간 등급(보통) 사진의 약 절반(14/30, 47%)이 등급이 한 칸 이동한다(zoom_test.py 재현).
# area_ratio 자체가 프레이밍에 의존하는 지표라는 근본 한계이며, 밴드를 넓혀 흔들림을 줄이면 그만큼
# 불량이 다시 A·B로 몰려 원래 버그로 회귀하므로 밴드 조정으로는 대응하지 않는다 — 근본 해결은
# 스케일 정보(촬영거리 등) 확보이며 별도 과제다.
_CRACK_AREA_RATIO_SEVERITY_BANDS: list[tuple[float, float]] = [
    (0.00138, 0.1),
    (0.00234, 0.3),
    (0.00396, 0.5),
    (0.00673, 0.7),
]

_AREA_RATIO_SEVERITY_BANDS_BY_TYPE: dict[str, list[tuple[float, float]]] = {
    "CRACK": _CRACK_AREA_RATIO_SEVERITY_BANDS,
}
_AREA_RATIO_SEVERITY_MAX = 0.9


def compute_severity_score(defect_type: str, area_ratio: float) -> float:
    """심각도 원점수 s ∈ [0, 1](높을수록 심각) — 규칙 문서 §3. 구간표는 하자 유형별로 다르다
    (모듈 docstring "균열 구간표 캘리브레이션" 참고)."""
    bands = _AREA_RATIO_SEVERITY_BANDS_BY_TYPE.get(defect_type, _DEFAULT_AREA_RATIO_SEVERITY_BANDS)
    s = _AREA_RATIO_SEVERITY_MAX
    for threshold, severity in bands:
        if area_ratio < threshold:
            s = severity
            break
    if defect_type == "REBAR_EXPOSURE":
        s = max(s, REBAR_EXPOSURE_SEVERITY_FLOOR)
    return s


def severity_score_to_grade(s: float) -> str:
    """s → g=1-s → A~E 버킷(§2). 상단 닫힘 A만 1.0 포함, 나머지는 [lo, hi) 반열림."""
    g = 1.0 - s
    if g >= 0.8:
        return "A"
    if g >= 0.6:
        return "B"
    if g >= 0.4:
        return "C"
    if g >= 0.2:
        return "D"
    return "E"


def compute_grade(defect_type: str, area_ratio: float) -> str:
    return severity_score_to_grade(compute_severity_score(defect_type, area_ratio))
