"""ai.chains.defect_detection_chain 단위 테스트.

- `_decode_image`: decompression bomb 방어(코드 리뷰 P2) — base64 길이 상한만으로는 고압축
  이미지(단색에 가까운 대형 PNG 등)가 작은 페이로드로 거대한 픽셀 버퍼로 디코딩되는 걸 막지
  못한다. PIL.Image.open을 몽키패치해 이 상황을 값싸게 재현한다.
- `_yolo_type_detections`(SPALLING/REBAR_EXPOSURE 전용 YOLO 경로)·`_crack_mask_to_detections`
  /`_crack_detections`(CRACK 전용 U-Net 경로)·`run_defect_detection_chain`(셋을 합치는 진입점):
  2026-07-27 6차 rebase 때 "모델 1개가 3클래스 전부 처리" 구조에서 "유형별 전용 모델 3개"
  구조로 재설계됐다(HF Hub 저장소가 그렇게 바뀜 — ai/core/yolo_client.py 모듈 docstring 참고).
"""
import base64
import io
import threading
import time

import numpy as np
import pytest
from PIL import Image

import ai.chains.defect_detection_chain as chain
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


def test_crack_mask_to_detections_extracts_component_bbox_area_ratio_and_confidence():
    mask = np.zeros((10, 10), dtype=bool)
    mask[2:7, 2:7] = True  # 5x5=25px 컴포넌트
    probability = np.zeros((10, 10), dtype=np.float32)
    probability[2:7, 2:7] = 0.8

    detections = chain._crack_mask_to_detections(mask, probability)

    assert len(detections) == 1
    detection = detections[0]
    assert detection.type == "CRACK"
    assert detection.area_ratio == pytest.approx(25 / 100)
    assert detection.confidence == pytest.approx(0.8)
    assert detection.bbox_x == pytest.approx(0.2)
    assert detection.bbox_y == pytest.approx(0.2)
    assert detection.bbox_w == pytest.approx(0.5)
    assert detection.bbox_h == pytest.approx(0.5)


def test_crack_mask_to_detections_filters_noise_specks_below_min_pixels():
    mask = np.zeros((10, 10), dtype=bool)
    mask[0, 0] = True  # 1px 스펙클 — MIN_CRACK_COMPONENT_PIXELS(20) 미만
    probability = np.zeros((10, 10), dtype=np.float32)
    probability[0, 0] = 0.9

    assert chain._crack_mask_to_detections(mask, probability) == []


def test_crack_mask_to_detections_separates_disjoint_components():
    mask = np.zeros((20, 20), dtype=bool)
    mask[1:6, 1:6] = True  # 25px
    mask[10:15, 10:15] = True  # 25px, 서로 연결 안 됨
    probability = np.full((20, 20), 0.7, dtype=np.float32)

    detections = chain._crack_mask_to_detections(mask, probability)

    assert len(detections) == 2
    assert all(d.type == "CRACK" for d in detections)


def test_crack_detections_thresholds_probability_before_component_analysis(monkeypatch):
    probability = np.zeros((10, 10), dtype=np.float32)
    probability[2:7, 2:7] = 0.9  # CRACK_MASK_THRESHOLD(0.5) 이상 25px

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
