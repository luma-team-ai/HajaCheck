"""하자 심각도 등급(A~E) 산정 — docs/conventions/하자_심각도_등급_규칙.md(HAJA-109) §2/§3 그대로 구현.

산정 위치는 그 문서 §4가 권장한 대로 "FastAPI 탐지 후처리"다(defect_explain_chain이 이미
severity_grade를 입력으로 기대하는 구조가 전제). Spring은 이 값을 그대로 저장만 하고 재계산하지
않는다. 임계값은 1차 초안(§3.2 "튜닝 대상")이라 상수 모듈로 하드코딩 — 후속 dev-11-03(관리자
하자 유형·등급 기준 관리)에서 DB화 예정.
"""
from __future__ import annotations

# 철근 노출은 "존재 자체가 고위험" — area_ratio와 무관하게 최소 s=0.6(등급 D 이하)에서 시작한다.
REBAR_EXPOSURE_SEVERITY_FLOOR = 0.6

# area_ratio(하자 마스크 픽셀 면적 / 이미지 전체 픽셀 면적) → 심각도 원점수 s 구간 매핑(공통 초안).
# (상한 미만, 마지막 구간만 이상) — 규칙 문서 §3.2 표 그대로.
_AREA_RATIO_SEVERITY_BANDS: list[tuple[float, float]] = [
    (0.01, 0.1),
    (0.03, 0.3),
    (0.07, 0.5),
    (0.15, 0.7),
]
_AREA_RATIO_SEVERITY_MAX = 0.9

# ultralytics model.names가 어떤 표기(영문/한글/대소문자)로 학습됐는지 몰라도 흡수하도록 느슨하게
# 정규화한다 — 학습 데이터 라벨 표기를 우리가 통제하지 않으므로 방어적으로 매핑.
_LABEL_ALIASES: dict[str, str] = {
    "crack": "CRACK",
    "균열": "CRACK",
    "spalling": "SPALLING",
    "박리": "SPALLING",
    "박락": "SPALLING",
    "박리박락": "SPALLING",
    "박리·박락": "SPALLING",
    "rebar_exposure": "REBAR_EXPOSURE",
    "rebar exposure": "REBAR_EXPOSURE",
    "rebar": "REBAR_EXPOSURE",
    "철근노출": "REBAR_EXPOSURE",
    "철근 노출": "REBAR_EXPOSURE",
}


def normalize_defect_type_label(raw_label: str) -> str | None:
    """모델이 반환한 클래스 라벨을 Spring DefectType enum 이름(CRACK/SPALLING/REBAR_EXPOSURE)으로
    정규화한다. 매핑 불가(3종 확정 클래스 밖의 라벨)는 None — 호출부가 해당 탐지를 건너뛴다."""
    key = raw_label.strip().lower()
    return _LABEL_ALIASES.get(key)


def compute_severity_score(defect_type: str, area_ratio: float) -> float:
    """심각도 원점수 s ∈ [0, 1](높을수록 심각) — 규칙 문서 §3."""
    s = _AREA_RATIO_SEVERITY_MAX
    for threshold, severity in _AREA_RATIO_SEVERITY_BANDS:
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
