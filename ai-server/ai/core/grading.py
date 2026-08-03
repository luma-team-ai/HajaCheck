"""하자 심각도 등급(A~E) 산정 — docs/conventions/하자_심각도_등급_규칙.md(HAJA-109) §2/§3 그대로 구현.

산정 위치는 그 문서 §4가 권장한 대로 "FastAPI 탐지 후처리"다(defect_explain_chain이 이미
severity_grade를 입력으로 기대하는 구조가 전제). Spring은 이 값을 그대로 저장만 하고 재계산하지
않는다. 임계값은 1차 초안(§3.2 "튜닝 대상")이라 상수 모듈로 하드코딩 — 후속 dev-11-03(관리자
하자 유형·등급 기준 관리)에서 DB화 예정.

## 균열(CRACK) 구간표 캘리브레이션 이력

### v2 — U-Net + 레터박스 + 전체마스크합 기준(2026-07-27, 오영석 실측 · 현재 값)

PR #973가 균열 탐지를 YOLO(`crack_yolov8s_seg.pt`)에서 U-Net(`crack_unet_resnet34.pt`)으로
교체하고, 등급 산정 단위도 "이미지당 최대 인스턴스 1개"에서 "이미지 전체(레터박스 패딩 제외
콘텐츠 영역) 마스크 합"으로 바꾸면서(`defect_detection_chain._crack_mask_to_detections`, PR #973
P1 리뷰) 아래 v1 밴드가 측정됐던 분포 자체가 사라졌다 — 두 변화 모두 area_ratio를 키우는
방향이라, v1 밴드를 그대로 쓰면 보통(정답) 150장 중 83장(55%)이 D·E(심각)로 과대경고됐다
(AI Hub 아파트 균열 470장 재검증, 2026-07-27 — 심각알람 정밀도 64.7%까지 떨어짐). 아래 값은
그 470장을 U-Net(`crack_unet_resnet34`) + 레터박스 전처리(`unet_client.py`, 학습 코드
`train_crack_unet.py`와 동일 조건) + 이미지 전체 마스크 합 기준으로 다시 측정해 재보정한 값이다.
v1과 동일한 로그 등간격 공식(`t1 = m2²/(α·m3)`, `r = (m2/t1)^(2/3)`, `α = 0.7683`, m2/m3 =
보통/불량 정답 area_ratio 중앙값)에 새 측정 중앙값만 대입했다 — 역산하면 v1 값이 정확히
재현되는 것으로 공식 자체는 검증됨.

⚠️ **이 값은 "모델=`crack_unet_resnet34` + 전처리=레터박스 + 집계=콘텐츠 영역 전체 마스크 합"
이라는 세 조건에 묶여 있다 — 셋 중 하나라도 바뀌면(모델 재학습, 전처리 변경, 집계 방식 변경)
재측정이 필요하다.** 이번 사고 자체가 "v1 값 옆에 측정 조건이 안 적혀 있어서, 모델·집계 기준이
바뀐 뒤에도 v1이 계속 쓰였던 것"이 원인이었다 — 같은 실수를 반복하지 않기 위해 조건을 명시해 둔다.

### v3 — 면적 × 어두움총량 min 합의(2026-07-28, 오영석 실측 · 현재 규칙)

area_ratio 단독으로는 "가늘지만 긴" 실금이 D·E로 과대판정된다 — area = 길이×폭인데 U-Net
마스크 폭은 실제 굵기와 무관하게 3~4px의 선 두께가 하한이라(640 입력에서 법적 기준 0.3mm ≈
0.2px, 해상도 하한) 사실상 길이만 재는 값이기 때문. 실사용 근접촬영 실금 사진 10장에서 이
문제가 재현돼(2026-07-27 보고: 실금 4장이 D), BLACKHAT 어두움총량(dark_ratio — 서브픽셀
영역에서 진짜 폭에 비례하는 광학 신호, 합성 검증 r=0.9987)을 두 번째 지표로 추가하고 **두 지표
등급 중 낮은 쪽을 채택**한다(min 합의 — 심각 판정엔 "넓게 퍼졌다"와 "짙다" 둘 다 필요).
실금은 면적(길이)만 높고 어두움이 낮아 걸러지고, 어두운 소형 오탐은 면적이 낮아 걸러진다.

- AI Hub 470장 촬영건 단위 CV(시드5×2-fold): 심각알람 F1 81.3% → **84.2%**(정밀도 83.4%/재현율 85.3%)
- 실사진 8장(탐지분): 실금 D 4장 중 2장 → C, 등급이 올라간 사진 0장(dark 단독 대체와 달리
  단조 하향만) / AI Hub 심각 균열 6장 전부 E 유지
- dark_ratio 산출은 `defect_detection_chain._crack_dark_ratio` — 캘리브레이션(cv_darksum.py)과
  동일 조건(BLACKHAT ellipse 15×15, 마스크 3×3 팽창 밴드, 255×콘텐츠픽셀 정규화)이어야 하며,
  이 조건이 바뀌면 dark 구간표도 재측정해야 한다.

### v4 — crack_unet_resnet34_v2(현장사진 70장 파인튜닝) 재교정(2026-08-03, 오영석 실측 · 현재 값)

균열 모델을 현장사진 70장으로 파인튜닝한 crack_unet_resnet34_v2로 교체(#998)하면서 v3 밴드가
측정됐던 분포 자체가 달라졌다(파인튜닝 모델은 마스크를 더 타이트하게 뽑아 area_ratio가 전반적으로
작아짐). AI Hub 아파트 균열 470장을 새 모델로 재추론해 area·dark 양쪽 밴드를 재교정했다(분위수
그리드 + 좌표하강, 목적함수=심각알람 F1 우선→3등급 정확도, 라벨 매핑 우수={A,B}/보통={C}/
불량={D,E}).

- AI Hub 470장: 심각알람 F1 86.5%→**87.9%**(정밀도 88.2%→88.2%, 재현율 88.8%→87.6%), 3등급
  정확도 70.2%→**79.6%**
- 파인튜닝 자체의 효과(별도 검증, 이 재교정과 무관): 현장 마스크 품질 GOOD 판정 0%→58.8%(20/34,
  실측 사진), AI Hub 원 성능(픽셀 F1) 하락 -2.6%p(망각 시험 허용선 -5%p 이내 PASS)

⚠️ 이 값은 "모델=`crack_unet_resnet34_v2`" 조건에 묶여 있다 — 모델이 다시 바뀌면 재측정 필요.

### v1 — YOLO + 최대 인스턴스 기준(2026-07-26, #953 · 폐기)

(구) `crack_yolov8s_seg.pt` + "이미지당 가장 큰 인스턴스 1개" 기준으로 측정됐던 값
(0.138/0.234/0.396/0.673%) — #973로 모델·집계 기준이 둘 다 바뀌면서 더 이상 유효하지 않다.
area_ratio(하자 마스크 픽셀 / 이미지 전체 픽셀)는 박리박락·철근노출 같은 면적형 하자에는
맞지만, 균열은 선형이라 아무리 심각해도 이 값이 매우 작다 — 이 근본 이유로 균열만 별도
구간표를 쓴다는 결정 자체는 v1에서 유효했고 v2에서도 유지된다(SPALLING/REBAR_EXPOSURE는
공통 구간표를 그대로 씀). 바뀐 건 균열 구간표의 구체적인 수치일 뿐이다.

⚠️ 프레이밍(촬영거리) 의존성 — area_ratio는 "촬영 조건이 유사하다"는 전제 하의 추정치라는
근본 한계가 있다(v1 zoom_test.py 재현: 같은 균열도 2배 가까이서 찍으면 area_ratio가 중앙값
1.5~1.8배로 변해 중간 등급 사진의 절반가량이 등급 한 칸 이동). **"스케일 정보(촬영거리 등)
확보가 근본 해결"이라는 v1 시점의 제안은 2026-07-27 실측으로 철회됐다** — AI Hub 라벨의
실제 촬영거리(65~312) 대비 검증한 결과, 폭·거리 상관이 너무 약해(ρ≈-0.26) 거리로 보정하면
상쇄가 아니라 과보정이 되어 판별력이 오히려 떨어졌다(AUC 0.87→0.78). 게다가 촬영거리 자체가
심각도와 교란(ρ(거리,정답등급)=-0.144, 심각한 균열일수록 가까이서 촬영하는 경향)돼 있어 독립
보정 변수로 못 쓴다. 밴드를 넓혀 흔들림을 줄이면 그만큼 불량이 다시 A·B로 몰려 원래 버그로
회귀하므로, 밴드 조정으로는 이 근본 한계에 대응하지 않는다 — 현재 미해결 상태로 남아 있다.
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

# 균열(CRACK) 전용 — v4, U-Net(crack_unet_resnet34_v2, 현장사진 70장 파인튜닝) + 레터박스 전처리
# + 콘텐츠 영역 전체 마스크 합 기준, AI Hub 아파트 균열 470장(2026-08-03) 재캘리브레이션. 측정
# 조건·근거는 모듈 docstring "균열 구간표 캘리브레이션 이력" 참고 — 모델·전처리·집계 방식이
# 바뀌면 재보정이 필요하다.
_CRACK_AREA_RATIO_SEVERITY_BANDS: list[tuple[float, float]] = [
    (0.00002956, 0.1),
    (0.00070104, 0.3),
    (0.00197858, 0.5),
    (0.00398633, 0.7),
]

# 균열(CRACK) 전용 — 어두움총량(dark_ratio) 구간표. v4(모듈 docstring 참고), AI Hub 아파트 균열
# 470장에서 area와 동일한 로그 등간격 공식으로 도출. dark_ratio 산출 조건이 바뀌면 재측정 필요.
# ⚠️ 마지막 항목(float('inf'), 0.7)은 이상(>=) 조건: dark >= 0.00194422일 때도 s=0.7(area보다 낮음)로
# 유지해서 min 합의에서 dark가 "등급 캡" 역할을 한다(v3 이후 설계).
_CRACK_DARK_RATIO_SEVERITY_BANDS: list[tuple[float, float]] = [
    (0.00000897, 0.1),
    (0.00010537, 0.3),
    (0.00151626, 0.5),
    (0.00194422, 0.7),
    (float('inf'), 0.7),  # 최상위 캡(area 0.9와 구분)
]

_AREA_RATIO_SEVERITY_BANDS_BY_TYPE: dict[str, list[tuple[float, float]]] = {
    "CRACK": _CRACK_AREA_RATIO_SEVERITY_BANDS,
}
_AREA_RATIO_SEVERITY_MAX = 0.9


def _band_severity(value: float, bands: list[tuple[float, float]]) -> float:
    for threshold, severity in bands:
        if value < threshold:
            return severity
    return _AREA_RATIO_SEVERITY_MAX


def compute_severity_score(defect_type: str, area_ratio: float) -> float:
    """심각도 원점수 s ∈ [0, 1](높을수록 심각) — 규칙 문서 §3. 구간표는 하자 유형별로 다르다
    (모듈 docstring "균열 구간표 캘리브레이션 이력" 참고)."""
    bands = _AREA_RATIO_SEVERITY_BANDS_BY_TYPE.get(defect_type, _DEFAULT_AREA_RATIO_SEVERITY_BANDS)
    s = _band_severity(area_ratio, bands)
    if defect_type == "REBAR_EXPOSURE":
        s = max(s, REBAR_EXPOSURE_SEVERITY_FLOOR)
    return s


def compute_crack_severity_score(area_ratio: float, dark_ratio: float) -> float:
    """균열 전용 2지표 min 합의(v3) — 면적·어두움 각각의 심각도 중 낮은 쪽. 근거·성능은 모듈
    docstring v3 항목 참고."""
    return min(
        _band_severity(area_ratio, _CRACK_AREA_RATIO_SEVERITY_BANDS),
        _band_severity(dark_ratio, _CRACK_DARK_RATIO_SEVERITY_BANDS),
    )


def compute_crack_grade(area_ratio: float, dark_ratio: float) -> str:
    return severity_score_to_grade(compute_crack_severity_score(area_ratio, dark_ratio))


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
