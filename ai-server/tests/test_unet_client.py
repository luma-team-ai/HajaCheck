"""ai.core.unet_client 전처리 계약 테스트(PR #973 후속, 2026-07-27 실측 — squash에서 레터박스로
전환). get_crack_model()이 다운로드하는 실제 체크포인트(HF Hub, ~100MB)는 건드리지 않는다 —
모델 호출부만 페이크로 교체해 리사이즈 레이아웃·ImageNet 정규화·패딩값·sigmoid·squeeze 계약이
조용히 깨지는 회귀를 잡는다.

이전(squash) 버전은 정사각 640x640 케이스만으로도 "출력 shape=(640,640)"을 검증할 수 있었지만,
그 방식은 종횡비 왜곡(squash) 자체를 잡지 못하는 맹점이 있었다(2026-07-27 리뷰 지적) — 아래
테스트는 비정사각 입력(세로 1080x1440 — 실측 검증셋 해상도, 가로 1920x1080)을 명시적으로 써서
콘텐츠 영역이 원본 종횡비를 유지하는지 직접 고정한다.
"""
import numpy as np
import pytest
import torch
from PIL import Image

from ai.core.unet_client import (
    CRACK_INPUT_SIZE,
    CrackPrediction,
    _letterbox_layout,
    predict_crack_probability,
)


class _RecordingModel:
    """입력 텐서를 기록하고 고정된 logits를 반환하는 페이크 U-Net."""

    def __init__(self, output: torch.Tensor):
        self.received_input: torch.Tensor | None = None
        self._output = output

    def __call__(self, x: torch.Tensor) -> torch.Tensor:
        self.received_input = x
        return self._output


def _zeros_model() -> _RecordingModel:
    return _RecordingModel(torch.zeros((1, 1, CRACK_INPUT_SIZE, CRACK_INPUT_SIZE)))


def test_letterbox_layout_fits_landscape_content_to_width_and_pads_top_bottom():
    # 1920x1080(16:9 가로) — 긴 변(가로)을 640에 맞추면 세로가 남아 상하에 패딩이 생긴다.
    top, left, content_h, content_w = _letterbox_layout(width=1920, height=1080)

    assert content_w == CRACK_INPUT_SIZE
    assert content_h == round(1080 * CRACK_INPUT_SIZE / 1920)
    assert left == 0
    assert top == (CRACK_INPUT_SIZE - content_h) // 2
    assert abs((content_w / content_h) - (1920 / 1080)) < 0.01


def test_letterbox_layout_fits_portrait_content_to_height_and_pads_left_right():
    # 1080x1440(3:4 세로, 실측 검증셋 해상도) — 긴 변(세로)을 640에 맞추면 가로가 남아 좌우에 패딩.
    top, left, content_h, content_w = _letterbox_layout(width=1080, height=1440)

    assert content_h == CRACK_INPUT_SIZE
    assert content_w == round(1080 * CRACK_INPUT_SIZE / 1440)
    assert top == 0
    assert left == (CRACK_INPUT_SIZE - content_w) // 2
    assert abs((content_w / content_h) - (1080 / 1440)) < 0.01


def test_letterbox_layout_square_input_has_no_padding():
    # 정사각 입력은 레터박스가 squash와 동일하게 퇴화한다(패딩 없음) — 기존 정사각 테스트들이
    # 여전히 유효한 이유.
    top, left, content_h, content_w = _letterbox_layout(width=CRACK_INPUT_SIZE, height=CRACK_INPUT_SIZE)

    assert (top, left, content_h, content_w) == (0, 0, CRACK_INPUT_SIZE, CRACK_INPUT_SIZE)


def test_predict_crack_probability_canvas_is_always_model_input_size():
    model = _zeros_model()
    image = Image.new("RGB", (1080, 1440), color=(0, 0, 0))

    prediction = predict_crack_probability(model, image)

    assert isinstance(prediction, CrackPrediction)
    assert model.received_input.shape == (1, 3, CRACK_INPUT_SIZE, CRACK_INPUT_SIZE)
    assert prediction.probability.shape == (CRACK_INPUT_SIZE, CRACK_INPUT_SIZE)


def test_predict_crack_probability_content_dims_preserve_source_aspect_ratio():
    model = _zeros_model()
    image = Image.new("RGB", (1080, 1440), color=(0, 0, 0))  # 세로가 긴 비정사각 입력

    prediction = predict_crack_probability(model, image)

    assert prediction.content_height == CRACK_INPUT_SIZE
    assert prediction.content_width < CRACK_INPUT_SIZE  # 좌우 패딩 발생
    assert prediction.content_top == 0
    assert prediction.content_left == (CRACK_INPUT_SIZE - prediction.content_width) // 2
    assert abs((prediction.content_width / prediction.content_height) - (1080 / 1440)) < 0.01


def test_predict_crack_probability_pads_with_normalized_black_outside_content():
    model = _zeros_model()
    # 흰색 이미지 — 콘텐츠(정규화된 흰색)와 패딩(정규화된 검정)이 뚜렷이 구분되게 한다.
    image = Image.new("RGB", (1080, 1440), color=(255, 255, 255))

    prediction = predict_crack_probability(model, image)
    tensor = model.received_input.numpy()

    # 좌측 열(0)은 content_left > 0(세로 긴 이미지라 좌우 패딩 발생)이라 항상 패딩 영역이다 —
    # 정규화 이전 픽셀값 0(검정)이 그대로 정규화된 값이어야 한다(albumentations
    # PadIfNeeded의 BORDER_CONSTANT=0과 동일 동작, 콘텐츠와 무관한 임의 상수가 아니다).
    assert prediction.content_left > 0
    expected_black_r = (0.0 - 0.485) / 0.229
    assert tensor[0, 0, 0, 0] == pytest.approx(expected_black_r, abs=1e-4)

    # 콘텐츠 영역 중앙 픽셀은 정규화된 흰색(1.0)이어야 한다.
    cy = prediction.content_top + prediction.content_height // 2
    cx = prediction.content_left + prediction.content_width // 2
    expected_white_r = (1.0 - 0.485) / 0.229
    assert tensor[0, 0, cy, cx] == pytest.approx(expected_white_r, abs=1e-4)


def test_predict_crack_probability_applies_imagenet_normalization_per_channel():
    model = _zeros_model()
    # 정사각 입력 — 레터박스가 패딩 없이 퇴화하므로(패딩 유무와 무관하게) 채널별 정규화 상수를
    # 직접 검증한다(R=1.0, G=B=0.0 스케일 기준).
    image = Image.new("RGB", (CRACK_INPUT_SIZE, CRACK_INPUT_SIZE), color=(255, 0, 0))

    predict_crack_probability(model, image)

    tensor = model.received_input.numpy()
    expected_r = (1.0 - 0.485) / 0.229
    expected_g = (0.0 - 0.456) / 0.224
    expected_b = (0.0 - 0.406) / 0.225

    assert np.allclose(tensor[0, 0], expected_r, atol=1e-4)  # R 채널
    assert np.allclose(tensor[0, 1], expected_g, atol=1e-4)  # G 채널
    assert np.allclose(tensor[0, 2], expected_b, atol=1e-4)  # B 채널


def test_predict_crack_probability_returns_float32_to_avoid_upcast():
    model = _zeros_model()
    image = Image.new("RGB", (10, 10))

    predict_crack_probability(model, image)

    assert model.received_input.dtype == torch.float32


def test_predict_crack_probability_applies_sigmoid_and_squeezes_to_2d():
    model = _RecordingModel(torch.full((1, 1, CRACK_INPUT_SIZE, CRACK_INPUT_SIZE), 10.0))
    image = Image.new("RGB", (10, 10))

    prediction = predict_crack_probability(model, image)

    assert prediction.probability.shape == (CRACK_INPUT_SIZE, CRACK_INPUT_SIZE)
    assert prediction.probability.min() >= 0.0
    assert prediction.probability.max() <= 1.0
    expected = 1.0 / (1.0 + np.exp(-10.0))
    assert np.allclose(prediction.probability, expected, atol=1e-4)
