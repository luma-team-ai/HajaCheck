"""ai.chains.defect_detection_chain 단위 테스트.

- `_decode_image`: decompression bomb 방어(코드 리뷰 P2) — base64 길이 상한만으로는 고압축
  이미지(단색에 가까운 대형 PNG 등)가 작은 페이로드로 거대한 픽셀 버퍼로 디코딩되는 걸 막지
  못한다. PIL.Image.open을 몽키패치해 이 상황을 값싸게 재현한다.
- `_yolo_type_detections`(SPALLING/REBAR_EXPOSURE 전용 YOLO 경로)·`_crack_mask_to_detections`
  /`_crack_detections`(CRACK 전용 U-Net 경로)·`run_defect_detection_chain`(셋을 합치는 진입점):
  2026-07-27 6차 rebase 때 "모델 1개가 3클래스 전부 처리" 구조에서 "유형별 전용 모델 3개"
  구조로 재설계됐다(HF Hub 저장소가 그렇게 바뀜 — ai/core/yolo_client.py 모듈 docstring 참고).
  이후 PR #973 메타 검수(P1/P2)로 등급 산정 단위·부분 실패 격리·과다 탐지 방지가 추가됐고,
  같은 날 실측(오영석, AI Hub 470장)으로 U-Net 추론이 squash에서 레터박스로 바뀌면서
  `_crack_detections`가 `predict_crack_probability`의 콘텐츠 영역(패딩 제외)만 잘라 넘기도록
  갱신됐다(ai/core/unet_client.CrackPrediction 참고) — `_crack_mask_to_detections` 자체는
  "이미 패딩이 제거된 배열"을 받는다는 전제라 이 파일의 테스트는 여전히 정사각 캔버스로
  직접 호출해도 유효하다. 아래 크랙 관련 테스트는 실제 배포 해상도(CRACK_INPUT_SIZE=640)를
  그대로 써서 비율 기반 상수(MIN_CRACK_COMPONENT_AREA_RATIO)가 의미 있게 검증되도록 한다
  (작은 캔버스에서는 비율이 사실상 0에 가까워져 필터링 자체가 무의미해진다).
"""
import base64
import io
import threading
import time

import numpy as np
import pytest
from PIL import Image

import ai.chains.defect_detection_chain as chain
from ai.core.unet_client import CRACK_INPUT_SIZE, CrackPrediction
from ai.chains.defect_detection_chain import (
    MAX_IMAGE_PIXELS,
    DefectDetectionError,
    DetectedDefect,
    _decode_image,
)


def _tiny_valid_png_base64() -> str:
    buf = io.BytesIO()
    Image.new("RGB", (2, 2), color=(255, 0, 0)).save(buf, format="PNG")
    return base64.b64encode(buf.getvalue()).decode("ascii")


def _dummy_image() -> Image.Image:
    return Image.new("RGB", (10, 10))


def _blank_crack_canvas() -> np.ndarray:
    return np.zeros((CRACK_INPUT_SIZE, CRACK_INPUT_SIZE), dtype=bool)


# dark 최상위 밴드(≥0.00194422, v4 재교정값 — #1447 P3) — v3 min 합의에서 등급이 area 축으로만
# 결정되게 고정하는 값.
# 어두움 축 자체를 검증하는 테스트가 아니면 이 값을 넘겨 기존 area 기반 기대치를 유지한다.
SEVERE_DARK_RATIO = 0.003


class _FakeHugeImage:
    """실제로는 아주 작은 파일이지만 헤더상 해상도만 거대하다고 선언하는 가짜 Image."""

    size = (10_000, 10_000)  # 100,000,000 px > MAX_IMAGE_PIXELS

    def convert(self, mode):  # noqa: D401 - 호출되면 안 되는 걸 확인하는 용도
        pytest.fail("픽셀 상한을 초과했는데 convert()가 호출됐다 — 전체 버퍼를 이미 할당한 것")


def test_decode_image_accepts_small_legit_image():
    image = _decode_image(_tiny_valid_png_base64())
    assert image.size == (2, 2)


def test_decode_image_rejects_oversized_pixel_count_before_full_decode(monkeypatch):
    monkeypatch.setattr(
        "PIL.Image.open",
        lambda *_args, **_kwargs: _FakeHugeImage(),
    )

    with pytest.raises(DefectDetectionError):
        _decode_image(_tiny_valid_png_base64())


def test_decode_image_accepts_pixel_count_exactly_at_limit(monkeypatch):
    class _AtLimitImage:
        size = (10_000, 4_000)  # 정확히 MAX_IMAGE_PIXELS(4천만)

        def convert(self, mode):
            return self

    assert _AtLimitImage.size[0] * _AtLimitImage.size[1] == MAX_IMAGE_PIXELS
    monkeypatch.setattr(
        "PIL.Image.open",
        lambda *_args, **_kwargs: _AtLimitImage(),
    )

    # 상한 자체는 거부 대상이 아니다(초과분만 거부) — 예외 없이 통과해야 한다.
    _decode_image(_tiny_valid_png_base64())


class _FakeBoxes:
    def __init__(self, xyxyn, confs):
        import torch

        self.xyxyn = torch.tensor(xyxyn)
        self.conf = torch.tensor(confs)
        self.cls = torch.tensor([0.0] * len(confs))

    def __len__(self):
        return len(self.xyxyn)


class _FakeResult:
    def __init__(self, boxes=None, masks=None):
        self.names = {}
        self.boxes = boxes
        self.masks = masks


class _FakeYoloModel:
    def __init__(self, result):
        self._result = result

    def predict(self, **_kwargs):
        return [self._result]


class _FakeMasks:
    """마스크 데이터를 시뮬레이션 — 전체 픽셀이 모두 마스크에 포함(area_ratio=1.0)."""

    def __init__(self):
        import torch

        self.data = [torch.ones((100, 100), dtype=torch.bool)]

    def __getitem__(self, index):
        return self.data[index]


def test_yolo_type_detections_includes_area_ratio_from_mask(monkeypatch):
    """DetectedDefect 결과에 area_ratio 필드가 포함되는지 검증(이슈 #802)."""
    boxes = _FakeBoxes([[0.1, 0.2, 0.3, 0.4]], [0.95])
    fake_model = _FakeYoloModel(_FakeResult(boxes=boxes, masks=_FakeMasks()))
    monkeypatch.setattr(chain, "get_yolo_model", lambda defect_type: fake_model)

    detections = chain._yolo_type_detections("SPALLING", _dummy_image())

    assert len(detections) == 1
    detection = detections[0]
    assert detection.type == "SPALLING"
    assert detection.confidence == 0.95
    assert detection.grade == "E"  # area_ratio=1.0 → 면적 비율 100%는 E 등급
    assert detection.area_ratio == 1.0
    assert detection.area_px == pytest.approx(100.0)  # 1.0 × (10×10 원본 픽셀)


def test_yolo_type_detections_area_ratio_fallback_to_bbox(monkeypatch):
    """세그멘테이션 마스크가 없는 체크포인트(masks=None)에서는 bbox 면적을 근사치로 쓴다(이슈 #802)."""
    boxes = _FakeBoxes([[0.1, 0.2, 0.3, 0.4]], [0.95])  # w=0.2, h=0.2 → area_ratio=0.04
    fake_model = _FakeYoloModel(_FakeResult(boxes=boxes, masks=None))
    monkeypatch.setattr(chain, "get_yolo_model", lambda defect_type: fake_model)

    detections = chain._yolo_type_detections("REBAR_EXPOSURE", _dummy_image())

    assert len(detections) == 1
    assert detections[0].type == "REBAR_EXPOSURE"
    assert detections[0].area_ratio == pytest.approx(0.2 * 0.2)


def test_yolo_type_detections_ignores_checkpoint_class_labels(monkeypatch):
    """rebar_exposure_yolov8n_seg.pt의 내부 클래스는 good/fair/poor(상태 등급)라 라벨 텍스트로는
    유형을 알 수 없다(저장소 README 확인, 2026-07-27) — 호출한 defect_type을 그대로 쓴다."""
    boxes = _FakeBoxes([[0.0, 0.0, 0.1, 0.1]], [0.5])
    fake_result = _FakeResult(boxes=boxes, masks=None)
    fake_result.names = {0: "poor"}  # 유형과 무관한 라벨이 와도
    fake_model = _FakeYoloModel(fake_result)
    monkeypatch.setattr(chain, "get_yolo_model", lambda defect_type: fake_model)

    detections = chain._yolo_type_detections("REBAR_EXPOSURE", _dummy_image())

    assert detections[0].type == "REBAR_EXPOSURE"


def test_yolo_type_detections_returns_empty_when_no_boxes(monkeypatch):
    fake_model = _FakeYoloModel(_FakeResult(boxes=None, masks=None))
    monkeypatch.setattr(chain, "get_yolo_model", lambda defect_type: fake_model)

    assert chain._yolo_type_detections("SPALLING", _dummy_image()) == []


def test_yolo_type_detections_clamps_confidence_below_manual_creation_sentinel(monkeypatch):
    """DefectRevisionService가 confidence==1.0을 "수동 생성" sentinel로 쓰므로, round() 후 1.0이
    될 수 있는 값은 AI 탐지분임을 잃지 않도록 클램프해야 한다(PR #973 P3 리뷰)."""
    boxes = _FakeBoxes([[0.0, 0.0, 0.1, 0.1]], [0.999999])
    fake_model = _FakeYoloModel(_FakeResult(boxes=boxes, masks=None))
    monkeypatch.setattr(chain, "get_yolo_model", lambda defect_type: fake_model)

    detections = chain._yolo_type_detections("SPALLING", _dummy_image())

    assert detections[0].confidence == chain.CONFIDENCE_CLAMP_MAX


def test_crack_mask_to_detections_extracts_component_bbox_area_ratio_and_confidence():
    mask = _blank_crack_canvas()
    mask[100:200, 100:200] = True  # 100x100=10,000px
    probability = np.zeros((CRACK_INPUT_SIZE, CRACK_INPUT_SIZE), dtype=np.float32)
    probability[100:200, 100:200] = 0.8

    detections = chain._crack_mask_to_detections(mask, probability, SEVERE_DARK_RATIO)

    assert len(detections) == 1
    detection = detections[0]
    assert detection.type == "CRACK"
    assert detection.area_ratio == pytest.approx(10_000 / (CRACK_INPUT_SIZE**2))
    assert detection.confidence == pytest.approx(0.8)
    assert detection.bbox_x == pytest.approx(100 / CRACK_INPUT_SIZE)
    assert detection.bbox_y == pytest.approx(100 / CRACK_INPUT_SIZE)
    assert detection.bbox_w == pytest.approx(100 / CRACK_INPUT_SIZE)
    assert detection.bbox_h == pytest.approx(100 / CRACK_INPUT_SIZE)


def test_crack_mask_to_detections_measures_straight_line_length_and_width():
    """width_px=2×면적/둘레·length_px=면적/폭 근사 — 4px 두께 200px 직선에서 검증."""
    mask = _blank_crack_canvas()
    mask[100:104, 100:300] = True  # 4×200=800px, 컷오프(~82px)보다 큼
    probability = np.where(mask, 0.9, 0.0).astype(np.float32)

    detection = chain._crack_mask_to_detections(mask, probability, SEVERE_DARK_RATIO)[0]

    assert detection.width_px == pytest.approx(4, rel=0.1)
    assert detection.length_px == pytest.approx(200, rel=0.1)
    assert detection.area_px == pytest.approx(800, rel=0.01)


def test_crack_mask_to_detections_scales_measurements_to_original_resolution():
    """scale(원본px/콘텐츠px)은 측정값만 원본 해상도로 환산하고 등급·면적비에는 영향이 없어야 한다."""
    mask = _blank_crack_canvas()
    mask[100:104, 100:300] = True
    probability = np.where(mask, 0.9, 0.0).astype(np.float32)

    base = chain._crack_mask_to_detections(mask, probability, SEVERE_DARK_RATIO, scale=1.0)[0]
    scaled = chain._crack_mask_to_detections(mask, probability, SEVERE_DARK_RATIO, scale=2.0)[0]

    assert scaled.width_px == pytest.approx(base.width_px * 2, rel=0.05)
    assert scaled.length_px == pytest.approx(base.length_px * 2, rel=0.05)
    assert scaled.area_px == pytest.approx(base.area_px * 4, rel=0.05)
    assert scaled.grade == base.grade
    assert scaled.area_ratio == base.area_ratio


def test_crack_mask_to_detections_returns_empty_for_blank_mask():
    mask = _blank_crack_canvas()
    probability = np.zeros((CRACK_INPUT_SIZE, CRACK_INPUT_SIZE), dtype=np.float32)

    assert chain._crack_mask_to_detections(mask, probability, SEVERE_DARK_RATIO) == []


def test_crack_mask_to_detections_filters_noise_specks_below_min_area_ratio():
    mask = _blank_crack_canvas()
    # MIN_CRACK_COMPONENT_AREA_RATIO(0.0002) 기준 640x640에서 컷오프는 약 82px — 5x5=25px는
    # 그보다 훨씬 작은 잡음 스펙클이다.
    mask[0:5, 0:5] = True
    probability = np.where(mask, 0.9, 0.0).astype(np.float32)

    assert chain._crack_mask_to_detections(mask, probability, SEVERE_DARK_RATIO) == []


def test_crack_mask_to_detections_separates_disjoint_components():
    mask = _blank_crack_canvas()
    mask[50:70, 50:70] = True  # 20x20=400px, 컷오프(~82px)보다 큼
    mask[400:420, 400:420] = True  # 서로 멀리 떨어진 두 번째 컴포넌트
    probability = np.where(mask, 0.7, 0.0).astype(np.float32)

    detections = chain._crack_mask_to_detections(mask, probability, SEVERE_DARK_RATIO)

    assert len(detections) == 2
    assert all(d.type == "CRACK" for d in detections)


def test_crack_mask_to_detections_caps_component_count_and_keeps_largest(monkeypatch):
    """노이즈 많은 벽면 1장이 수십~수백 개의 탐지 행을 만드는 것을 막는 상한(PR #973 P2-2)."""
    monkeypatch.setattr(chain, "MAX_CRACK_COMPONENTS", 5)

    mask = _blank_crack_canvas()
    probability = np.zeros((CRACK_INPUT_SIZE, CRACK_INPUT_SIZE), dtype=np.float32)
    # 서로 떨어진 10개 컴포넌트, 크기를 10~100px 간격으로 달리해 "가장 큰 5개만 남는지" 구분 가능하게 함.
    sizes = list(range(10, 20))  # side lengths: 10..19 → area 100..361
    for i, side in enumerate(sizes):
        row, col = divmod(i, 5)
        y0, x0 = row * 100 + 5, col * 100 + 5
        mask[y0 : y0 + side, x0 : x0 + side] = True
        probability[y0 : y0 + side, x0 : x0 + side] = 0.9

    detections = chain._crack_mask_to_detections(mask, probability, SEVERE_DARK_RATIO)

    assert len(detections) == 5
    kept_areas = sorted(round(d.area_ratio * CRACK_INPUT_SIZE**2) for d in detections)
    largest_five_true_areas = sorted(side * side for side in sizes)[-5:]
    assert kept_areas == largest_five_true_areas


def test_crack_mask_to_detections_grade_is_invariant_to_fragmentation():
    """PR #973 P1 리뷰 — 같은 총 면적이 1덩어리든 여러 조각으로 끊기든 등급이 같아야 한다.

    실측 재현: 총면적 0.876%가 1덩어리면 E, 4조각(각 0.156%)이면 B로 갈리던 문제(리뷰 원문)를
    고정한다. 등급은 이제 컴포넌트별이 아니라 이미지 전체 마스크 면적 기준 1회 산정이다.
    """
    single = _blank_crack_canvas()
    single[100:180, 100:180] = True  # 80x80=6,400px

    fragmented = _blank_crack_canvas()
    for oy, ox in [(50, 50), (250, 50), (50, 250), (250, 250)]:
        fragmented[oy : oy + 40, ox : ox + 40] = True  # 40x40=1,600px * 4 = 6,400px

    assert single.sum() == fragmented.sum()  # 총 면적 동일 전제

    probability_single = np.where(single, 0.9, 0.0).astype(np.float32)
    probability_fragmented = np.where(fragmented, 0.9, 0.0).astype(np.float32)

    single_detections = chain._crack_mask_to_detections(single, probability_single, SEVERE_DARK_RATIO)
    fragmented_detections = chain._crack_mask_to_detections(
        fragmented, probability_fragmented, SEVERE_DARK_RATIO
    )

    assert len(single_detections) == 1
    assert len(fragmented_detections) == 4
    single_grade = single_detections[0].grade
    assert all(d.grade == single_grade for d in fragmented_detections)


def test_crack_mask_to_detections_width_is_stable_for_close_merged_fragments():
    """#1447 P2 — 같은 label로 병합되지만(MORPH_CLOSE) 원래 마스크상 끊긴 두 조각의 width_px가
    연속된 단일 블록 대비 부당하게 작아지면 안 된다.

    close 커널(3×3 ellipse)이 메울 수 있는 1px 간극으로 직선을 2조각으로 끊으면 하나의 label로
    재병합되지만(단일 탐지), 둘레 계산에 원래(파편화된) 마스크를 쓰면 조각마다 별도 외곽선이
    잡혀 둘레가 부풀고 width_px가 실제보다 작게 나온다(수정 전 재현: width -1.0%, 재현 조각
    수가 늘수록 왜곡도 커짐). 수정 후에는 닫힌 연결영역 기준으로 둘레를 구해 훨씬 근접해야 한다.

    ⚠️ close 자체가 다리를 놓는 자리마다 경계에 미세한 요철을 남기므로(둥근 커널의 부작용),
    간극 개수가 많아질수록 이 수정으로도 완전히 없앨 수 없는 잔여 오차가 남는다(8조각·1px
    간극 인위적 구성 실측: 수정 전 -6.8% → 수정 후 -6.2%, 절반도 못 줄임). 이 테스트는 실제
    임계값 잡음이 보통 만드는 수준(간극 1곳)으로 한정한다 — 완전 해결이 아니라 "가장 흔한
    파편화 패턴에서 유의미하게 개선"이 이 수정의 범위다.
    """
    single = _blank_crack_canvas()
    single[100:104, 100:300] = True  # 200x4=800px 연속 직선

    fragmented = single.copy()
    fragmented[100:104, 199] = False  # 1px 간극 — close(3x3)로 재병합되는 최소 재현 케이스

    probability_single = np.where(single, 0.9, 0.0).astype(np.float32)
    probability_fragmented = np.where(fragmented, 0.9, 0.0).astype(np.float32)

    single_detections = chain._crack_mask_to_detections(single, probability_single, 0.0)
    fragmented_detections = chain._crack_mask_to_detections(fragmented, probability_fragmented, 0.0)

    assert len(fragmented_detections) == 1  # close로 하나의 label에 재병합됐는지 전제 확인
    single_width = single_detections[0].width_px
    fragmented_width = fragmented_detections[0].width_px
    assert fragmented_width == pytest.approx(single_width, rel=0.03)  # 수정 전 이 케이스: -1.0%


def test_crack_mask_to_detections_caps_grade_for_bright_hairline_crack():
    """v4 min 합의 — 면적(길이)만 크고 어두움이 낮은 실금은 등급이 어두움 축으로 제한된다(2026-08-03 재보정).

    실사용 근접촬영 실금 사진에서 area_ratio가 높게 나오는 케이스: 같은 마스크라도
    dark_ratio가 낮으면(실금) 등급이 어두움 축으로 제한되어야 한다.
    """
    mask = _blank_crack_canvas()
    mask[100:200, 100:200] = True  # area 2.4% → area_s=0.9
    probability = np.where(mask, 0.9, 0.0).astype(np.float32)

    severe = chain._crack_mask_to_detections(mask, probability, SEVERE_DARK_RATIO)
    hairline = chain._crack_mask_to_detections(mask, probability, 0.0005)  # dark_s=0.5 (0.00010537 < 0.0005 < 0.00151626)

    assert severe[0].grade == "E"  # min(0.9, fallback 0.9)=0.9, SEVERE_DARK_RATIO=0.003 >= 0.00194422
    assert hairline[0].grade == "C"  # min(0.9, 0.5)=0.5


def test_crack_dark_ratio_separates_dark_line_from_faint_line():
    """_crack_dark_ratio — 같은 마스크에서 짙은 선이 옅은 선보다 큰 값을 내야 하고, 빈 마스크는 0."""
    from PIL import Image as PILImage

    size = 100
    mask = np.zeros((size, size), dtype=bool)
    mask[50, 10:90] = True  # 가로선 1px

    def image_with_line(line_value: int) -> PILImage.Image:
        arr = np.full((size, size, 3), 200, dtype=np.uint8)
        arr[50, 10:90] = line_value
        return PILImage.fromarray(arr)

    dark = chain._crack_dark_ratio(image_with_line(20), mask)
    faint = chain._crack_dark_ratio(image_with_line(180), mask)

    assert dark > faint > 0.0
    assert chain._crack_dark_ratio(image_with_line(20), np.zeros((size, size), dtype=bool)) == 0.0


def test_crack_mask_to_detections_clamps_confidence_below_manual_creation_sentinel():
    mask = _blank_crack_canvas()
    mask[100:200, 100:200] = True
    probability = np.where(mask, 0.999999, 0.0).astype(np.float32)

    detections = chain._crack_mask_to_detections(mask, probability, SEVERE_DARK_RATIO)

    assert detections[0].confidence == chain.CONFIDENCE_CLAMP_MAX


def test_crack_detections_thresholds_probability_before_component_analysis(monkeypatch):
    probability = np.zeros((CRACK_INPUT_SIZE, CRACK_INPUT_SIZE), dtype=np.float32)
    probability[100:200, 100:200] = 0.9  # CRACK_MASK_THRESHOLD(0.5) 이상

    monkeypatch.setattr(chain, "get_crack_model", lambda: "fake-crack-model")
    monkeypatch.setattr(
        chain,
        "predict_crack_probability",
        lambda model, image: CrackPrediction(
            probability=probability,
            content_top=0, content_left=0,
            content_height=CRACK_INPUT_SIZE, content_width=CRACK_INPUT_SIZE,
        ),
    )

    detections, content_probability = chain._crack_detections(_dummy_image())

    assert len(detections) == 1
    assert detections[0].type == "CRACK"
    assert content_probability is not None


def test_crack_detections_returns_none_content_probability_when_no_detections(monkeypatch):
    """#1658 — 균열이 없으면 상위(run_defect_detection_chain)가 CRACK width_mm 적용 대상이
    아님을 판단할 수 있게 content_probability 자리도 None이어야 한다."""
    probability = np.zeros((CRACK_INPUT_SIZE, CRACK_INPUT_SIZE), dtype=np.float32)  # 전부 0 → 마스크 없음

    monkeypatch.setattr(chain, "get_crack_model", lambda: "fake-crack-model")
    monkeypatch.setattr(
        chain,
        "predict_crack_probability",
        lambda model, image: CrackPrediction(
            probability=probability,
            content_top=0, content_left=0,
            content_height=CRACK_INPUT_SIZE, content_width=CRACK_INPUT_SIZE,
        ),
    )

    detections, content_probability = chain._crack_detections(_dummy_image())

    assert detections == []
    assert content_probability is None


def test_crack_detections_excludes_letterbox_padding_from_area_ratio_and_bbox(monkeypatch):
    """레터박스 패딩이 area_ratio 분모·bbox 정규화에 섞이지 않는지 고정(2026-07-27 실측 수정 리뷰).

    세로가 긴 원본(예: 1080x1440)을 흉내내 콘텐츠가 640 캔버스의 왼쪽 절반만 차지하게 하고
    (좌우에 패딩), 콘텐츠 우측 가장자리에 붙은 성분을 만든다 — 패딩 오프셋을 안 빼면 bbox가
    실제 위치보다 왼쪽으로 밀리고, area_ratio 분모에 패딩까지 포함되면 절반으로 줄어든다.
    """
    content_top, content_left = 0, 160
    content_height, content_width = CRACK_INPUT_SIZE, 320

    probability = np.zeros((CRACK_INPUT_SIZE, CRACK_INPUT_SIZE), dtype=np.float32)
    # 콘텐츠(열 160~479) 우측 가장자리에 붙은 100x50 성분 — 원본 캔버스 좌표로 [430:480].
    probability[100:200, 430:480] = 0.9

    monkeypatch.setattr(chain, "get_crack_model", lambda: "fake-crack-model")
    monkeypatch.setattr(
        chain,
        "predict_crack_probability",
        lambda model, image: CrackPrediction(
            probability=probability,
            content_top=content_top, content_left=content_left,
            content_height=content_height, content_width=content_width,
        ),
    )

    detections, _content_probability = chain._crack_detections(_dummy_image())

    assert len(detections) == 1
    detection = detections[0]
    # area_ratio 분모는 콘텐츠 픽셀 수(640*320)여야 한다 — 패딩 포함 640*640이면 절반으로 준다.
    expected_area_ratio = (100 * 50) / (content_height * content_width)
    assert detection.area_ratio == pytest.approx(expected_area_ratio)
    # 콘텐츠 우측 가장자리에 붙은 성분이므로 bbox_x+bbox_w는 콘텐츠 기준 1.0에 딱 붙어야 한다 —
    # 패딩 오프셋(160px)이 안 빠지면 479/640≈0.75 근처로 훨씬 작게 나온다.
    assert detection.bbox_x + detection.bbox_w == pytest.approx(1.0)


def _make_crack_detection() -> DetectedDefect:
    return DetectedDefect(
        type="CRACK", bbox_x=0.1, bbox_y=0.1, bbox_w=0.1, bbox_h=0.1,
        confidence=0.8, grade="B", area_ratio=0.01,
    )


def _detection(defect_type: str, x: float, y: float, w: float, h: float) -> DetectedDefect:
    return DetectedDefect(
        type=defect_type, bbox_x=x, bbox_y=y, bbox_w=w, bbox_h=h,
        confidence=0.9, grade="C", area_ratio=w * h,
    )



def test_run_defect_detection_chain_aggregates_crack_spalling_rebar_exposure(monkeypatch):
    """세 모델(U-Net 균열 + YOLO 박리박락 + YOLO 철근노출)의 결과가 전부 합쳐지는지 검증."""
    monkeypatch.setattr(chain, "_crack_detections", lambda image: ([_make_crack_detection()], None))
    monkeypatch.setattr(chain, "_safe_detect_card", lambda image: None)

    fake_model = _FakeYoloModel(
        _FakeResult(boxes=_FakeBoxes([[0.0, 0.0, 0.1, 0.1]], [0.9]), masks=None)
    )
    monkeypatch.setattr(chain, "get_yolo_model", lambda defect_type: fake_model)

    detections = chain.run_defect_detection_chain(_tiny_valid_png_base64())

    assert sorted(d.type for d in detections) == ["CRACK", "REBAR_EXPOSURE", "SPALLING"]


def test_run_defect_detection_chain_isolates_single_type_failure(monkeypatch):
    """PR #973 P2-1 — U-Net(신규 의존성) 하나가 예외를 던져도 나머지 유형은 계속 진행해야 한다."""

    def _boom(image):
        raise RuntimeError("torch boom")

    monkeypatch.setattr(chain, "_crack_detections", _boom)
    monkeypatch.setattr(chain, "_safe_detect_card", lambda image: None)
    fake_model = _FakeYoloModel(
        _FakeResult(boxes=_FakeBoxes([[0.0, 0.0, 0.1, 0.1]], [0.9]), masks=None)
    )
    monkeypatch.setattr(chain, "get_yolo_model", lambda defect_type: fake_model)

    detections = chain.run_defect_detection_chain(_tiny_valid_png_base64())

    assert sorted(d.type for d in detections) == ["REBAR_EXPOSURE", "SPALLING"]


def test_run_defect_detection_chain_raises_when_every_type_fails(monkeypatch):
    def _boom(*_args, **_kwargs):
        raise RuntimeError("boom")

    monkeypatch.setattr(chain, "_crack_detections", lambda image: _boom())
    monkeypatch.setattr(chain, "get_yolo_model", lambda defect_type: _boom())

    with pytest.raises(DefectDetectionError):
        chain.run_defect_detection_chain(_tiny_valid_png_base64())


def test_run_defect_detection_chain_shares_single_card_detection_across_types(monkeypatch):
    """#1658 — 세 유형에 detection이 있어도 카드 검출은 이미지당 1회만 호출되고, CRACK
    width_mm과 SPALLING/REBAR_EXPOSURE area_mm2에 같은 스케일이 공유돼야 한다."""
    import numpy as np

    from ai.core.card_client import CardDetectionResult

    crack_detection = _make_crack_detection()
    crack_detection.width_px = 4.0
    fake_content_probability = np.zeros((10, 10), dtype=np.float32)
    monkeypatch.setattr(
        chain, "_crack_detections", lambda image: ([crack_detection], fake_content_probability)
    )

    call_count = {"n": 0}

    def _fake_detect_card(image):
        call_count["n"] += 1
        return CardDetectionResult(long_px=100.0, short_px=63.0, confidence=0.9, method="quad")

    monkeypatch.setattr(chain, "detect_card", _fake_detect_card)

    applied_width_mm = {}

    def _fake_apply_crack_width_mm(detections, image, content_probability, card_scale_mm_per_px):
        applied_width_mm["scale"] = card_scale_mm_per_px
        applied_width_mm["content_probability"] = content_probability

    monkeypatch.setattr(chain, "_apply_crack_width_mm", _fake_apply_crack_width_mm)

    fake_model = _FakeYoloModel(
        _FakeResult(boxes=_FakeBoxes([[0.0, 0.0, 0.1, 0.1]], [0.9]), masks=None)
    )
    monkeypatch.setattr(chain, "get_yolo_model", lambda defect_type: fake_model)

    detections = chain.run_defect_detection_chain(_tiny_valid_png_base64())

    assert call_count["n"] == 1  # 카드 검출은 이미지당 1회만
    expected_scale = chain.CARD_LONG_MM / 100.0
    assert applied_width_mm["scale"] == pytest.approx(expected_scale)
    assert applied_width_mm["content_probability"] is fake_content_probability

    spalling_or_rebar = [d for d in detections if d.type in ("SPALLING", "REBAR_EXPOSURE")]
    assert spalling_or_rebar  # YOLO 탐지가 실제로 섞여 들어왔는지 전제 확인
    for detection in spalling_or_rebar:
        assert detection.area_mm2 == pytest.approx(detection.area_px * expected_scale**2, rel=1e-6)
    crack_result = next(d for d in detections if d.type == "CRACK")
    assert crack_result.area_mm2 is None  # CRACK은 area_mm2 대상이 아니다


def test_run_defect_detection_chain_skips_card_detection_when_no_detections(monkeypatch):
    """detection이 하나도 없으면 카드 검출 자체를 호출하지 않는다(#1547 P2 최적화 확장)."""
    monkeypatch.setattr(chain, "_crack_detections", lambda image: ([], None))
    fake_model = _FakeYoloModel(_FakeResult(boxes=None, masks=None))
    monkeypatch.setattr(chain, "get_yolo_model", lambda defect_type: fake_model)

    call_count = {"n": 0}

    def _fake_detect_card(image):
        call_count["n"] += 1
        return None

    monkeypatch.setattr(chain, "detect_card", _fake_detect_card)

    detections = chain.run_defect_detection_chain(_tiny_valid_png_base64())

    assert detections == []
    assert call_count["n"] == 0


def test_run_defect_detection_chain_card_miss_leaves_mm_fields_none(monkeypatch):
    """카드 미검출 시 width_mm/area_mm2 모두 None으로 유지돼야 한다(신뢰 구간 밖 폴백)."""
    crack_detection = _make_crack_detection()
    monkeypatch.setattr(chain, "_crack_detections", lambda image: ([crack_detection], None))
    monkeypatch.setattr(chain, "detect_card", lambda image: None)

    fake_model = _FakeYoloModel(
        _FakeResult(boxes=_FakeBoxes([[0.0, 0.0, 0.1, 0.1]], [0.9]), masks=None)
    )
    monkeypatch.setattr(chain, "get_yolo_model", lambda defect_type: fake_model)

    detections = chain.run_defect_detection_chain(_tiny_valid_png_base64())

    for detection in detections:
        assert detection.width_mm is None
        assert detection.area_mm2 is None


def test_run_defect_detection_chain_survives_card_scale_application_failure(monkeypatch):
    """코드 리뷰 P1 — 카드 스케일 적용(_apply_crack_width_mm/_apply_area_mm2) 중 예외가 나도
    이미 확보한 세 유형 detections는 그대로 반환돼야 한다.

    카드 검출 호출부를 run_defect_detection_chain으로 끌어올리며(#1658) 스케일 적용 블록이
    per-type try/except 밖으로 나갔다 — 여기서 예외가 새면 라우터 최상위 except가 응답 전체를
    VISION_INFERENCE_FAILED로 실패 처리해, mm 병기라는 부가 정보 실패가 이미 확보한 핵심 탐지
    결과까지 통째로 날린다(#1547 P1이 막았던 실패 모드의 확대 재발).
    """
    import numpy as np

    from ai.core.card_client import CardDetectionResult

    crack_detection = _make_crack_detection()
    crack_detection.width_px = 4.0
    fake_content_probability = np.zeros((10, 10), dtype=np.float32)
    monkeypatch.setattr(
        chain, "_crack_detections", lambda image: ([crack_detection], fake_content_probability)
    )
    monkeypatch.setattr(
        chain,
        "detect_card",
        lambda image: CardDetectionResult(long_px=100.0, short_px=63.0, confidence=0.9, method="quad"),
    )

    def _boom(*_args, **_kwargs):
        raise RuntimeError("measure_crack_width_mm boom")

    monkeypatch.setattr(chain, "_apply_crack_width_mm", _boom)

    fake_model = _FakeYoloModel(
        _FakeResult(boxes=_FakeBoxes([[0.0, 0.0, 0.1, 0.1]], [0.9]), masks=None)
    )
    monkeypatch.setattr(chain, "get_yolo_model", lambda defect_type: fake_model)

    detections = chain.run_defect_detection_chain(_tiny_valid_png_base64())  # 예외 없이 반환돼야 함

    assert sorted(d.type for d in detections) == ["CRACK", "REBAR_EXPOSURE", "SPALLING"]


def test_apply_area_mm2_skips_crack_and_missing_area_px():
    crack = _make_crack_detection()
    assert crack.area_px is None
    spalling = _detection("SPALLING", 0.0, 0.0, 0.2, 0.2)
    spalling.area_px = 400.0

    chain._apply_area_mm2([crack, spalling], card_scale_mm_per_px=0.5)

    assert crack.area_mm2 is None  # CRACK 제외
    assert spalling.area_mm2 == pytest.approx(400.0 * 0.5 * 0.5)


def test_safe_detect_card_swallows_exception(monkeypatch):
    def _boom(image):
        raise RuntimeError("card boom")

    monkeypatch.setattr(chain, "detect_card", _boom)

    assert chain._safe_detect_card(_dummy_image()) is None


def test_run_defect_detection_chain_serializes_concurrent_predict_calls(monkeypatch):
    """코드 리뷰 P2 — get_yolo_model()이 반환하는 인스턴스는 프로세스 전역 공유(@lru_cache)라,
    ultralytics YOLO.predict가 스레드 세이프를 보장하지 않는다. FastAPI가 동기 핸들러를
    threadpool에서 실행하고 Spring analysisTaskExecutor(core=max=2)가 서로 다른 회차를 동시에
    분석하면 최대 2개 요청이 predict를 동시 호출할 수 있어, yolo_client.predict()의 락으로
    직렬화해야 한다 — 가짜 모델의 predict 구간을 넓혀(sleep) 여러 스레드가 실제로 겹칠 기회를
    주고, 락이 없으면 잡혔을 동시 진입(max_active > 1)이 없는지 고정한다. U-Net 경로는 이 락과
    무관하므로(yolo_client.predict를 거치지 않음) `_crack_detections`를 no-op으로 고정해 테스트를
    YOLO 동시성 계약에만 집중시킨다.
    """
    active = 0
    max_active = 0
    counter_lock = threading.Lock()

    class _SleepyResult:
        names: dict = {}
        boxes = None
        masks = None

    class _SleepyModel:
        def predict(self, **_kwargs):
            nonlocal active, max_active
            with counter_lock:
                active += 1
                max_active = max(max_active, active)
            time.sleep(0.05)  # predict 호출 구간을 늘려 락이 없으면 겹칠 기회를 실제로 만든다
            with counter_lock:
                active -= 1
            return [_SleepyResult()]

    monkeypatch.setattr(chain, "_crack_detections", lambda image: ([], None))
    fake_model = _SleepyModel()
    monkeypatch.setattr(chain, "get_yolo_model", lambda defect_type: fake_model)

    image_b64 = _tiny_valid_png_base64()
    threads = [
        threading.Thread(target=chain.run_defect_detection_chain, args=(image_b64,))
        for _ in range(4)
    ]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert max_active == 1
