"""ai.chains.defect_detection_chain 단위 테스트.

- `_decode_image`: decompression bomb 방어(코드 리뷰 P2) — base64 길이 상한만으로는 고압축
  이미지(단색에 가까운 대형 PNG 등)가 작은 페이로드로 거대한 픽셀 버퍼로 디코딩되는 걸 막지
  못한다. PIL.Image.open을 몽키패치해 이 상황을 값싸게 재현한다.
- `_yolo_type_detections`(SPALLING/REBAR_EXPOSURE 전용 YOLO 경로)·`_crack_mask_to_detections`
  /`_crack_detections`(CRACK 전용 U-Net 경로)·`run_defect_detection_chain`(셋을 합치는 진입점):
  2026-07-27 6차 rebase 때 "모델 1개가 3클래스 전부 처리" 구조에서 "유형별 전용 모델 3개"
  구조로 재설계됐다(HF Hub 저장소가 그렇게 바뀜 — ai/core/yolo_client.py 모듈 docstring 참고).
  이후 PR #973 메타 검수(P1/P2)로 등급 산정 단위·부분 실패 격리·과다 탐지 방지가 추가됐다 —
  아래 크랙 관련 테스트는 실제 배포 해상도(CRACK_INPUT_SIZE=640)를 그대로 써서 비율 기반
  상수(MIN_CRACK_COMPONENT_AREA_RATIO)가 의미 있게 검증되도록 한다(작은 캔버스에서는 비율이
  사실상 0에 가까워져 필터링 자체가 무의미해진다).
"""
import base64
import io
import threading
import time

import numpy as np
import pytest
from PIL import Image

import ai.chains.defect_detection_chain as chain
from ai.core.unet_client import CRACK_INPUT_SIZE
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

    detections = chain._crack_mask_to_detections(mask, probability)

    assert len(detections) == 1
    detection = detections[0]
    assert detection.type == "CRACK"
    assert detection.area_ratio == pytest.approx(10_000 / (CRACK_INPUT_SIZE**2))
    assert detection.confidence == pytest.approx(0.8)
    assert detection.bbox_x == pytest.approx(100 / CRACK_INPUT_SIZE)
    assert detection.bbox_y == pytest.approx(100 / CRACK_INPUT_SIZE)
    assert detection.bbox_w == pytest.approx(100 / CRACK_INPUT_SIZE)
    assert detection.bbox_h == pytest.approx(100 / CRACK_INPUT_SIZE)


def test_crack_mask_to_detections_returns_empty_for_blank_mask():
    mask = _blank_crack_canvas()
    probability = np.zeros((CRACK_INPUT_SIZE, CRACK_INPUT_SIZE), dtype=np.float32)

    assert chain._crack_mask_to_detections(mask, probability) == []


def test_crack_mask_to_detections_filters_noise_specks_below_min_area_ratio():
    mask = _blank_crack_canvas()
    # MIN_CRACK_COMPONENT_AREA_RATIO(0.0002) 기준 640x640에서 컷오프는 약 82px — 5x5=25px는
    # 그보다 훨씬 작은 잡음 스펙클이다.
    mask[0:5, 0:5] = True
    probability = np.where(mask, 0.9, 0.0).astype(np.float32)

    assert chain._crack_mask_to_detections(mask, probability) == []


def test_crack_mask_to_detections_separates_disjoint_components():
    mask = _blank_crack_canvas()
    mask[50:70, 50:70] = True  # 20x20=400px, 컷오프(~82px)보다 큼
    mask[400:420, 400:420] = True  # 서로 멀리 떨어진 두 번째 컴포넌트
    probability = np.where(mask, 0.7, 0.0).astype(np.float32)

    detections = chain._crack_mask_to_detections(mask, probability)

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

    detections = chain._crack_mask_to_detections(mask, probability)

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

    single_detections = chain._crack_mask_to_detections(single, probability_single)
    fragmented_detections = chain._crack_mask_to_detections(fragmented, probability_fragmented)

    assert len(single_detections) == 1
    assert len(fragmented_detections) == 4
    single_grade = single_detections[0].grade
    assert all(d.grade == single_grade for d in fragmented_detections)


def test_crack_mask_to_detections_clamps_confidence_below_manual_creation_sentinel():
    mask = _blank_crack_canvas()
    mask[100:200, 100:200] = True
    probability = np.where(mask, 0.999999, 0.0).astype(np.float32)

    detections = chain._crack_mask_to_detections(mask, probability)

    assert detections[0].confidence == chain.CONFIDENCE_CLAMP_MAX


def test_crack_detections_thresholds_probability_before_component_analysis(monkeypatch):
    probability = np.zeros((CRACK_INPUT_SIZE, CRACK_INPUT_SIZE), dtype=np.float32)
    probability[100:200, 100:200] = 0.9  # CRACK_MASK_THRESHOLD(0.5) 이상

    monkeypatch.setattr(chain, "get_crack_model", lambda: "fake-crack-model")
    monkeypatch.setattr(
        chain, "predict_crack_probability", lambda model, image: probability
    )

    detections = chain._crack_detections(_dummy_image())

    assert len(detections) == 1
    assert detections[0].type == "CRACK"


def _make_crack_detection() -> DetectedDefect:
    return DetectedDefect(
        type="CRACK", bbox_x=0.1, bbox_y=0.1, bbox_w=0.1, bbox_h=0.1,
        confidence=0.8, grade="B", area_ratio=0.01,
    )


def test_run_defect_detection_chain_aggregates_crack_spalling_rebar_exposure(monkeypatch):
    """세 모델(U-Net 균열 + YOLO 박리박락 + YOLO 철근노출)의 결과가 전부 합쳐지는지 검증."""
    monkeypatch.setattr(chain, "_crack_detections", lambda image: [_make_crack_detection()])

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

    monkeypatch.setattr(chain, "_crack_detections", lambda image: [])
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
