"""ai.core.unet_client.predict_crack_probability 전처리 계약 테스트(PR #973 P2-5 리뷰).

get_crack_model()이 다운로드하는 실제 체크포인트(HF Hub, ~100MB)는 건드리지 않는다 — 모델
호출부만 페이크로 교체해 리사이즈 크기·ImageNet 정규화·축 순서(transpose)·sigmoid·squeeze
계약이 조용히 깨지는 회귀(예: 축이 뒤바뀌거나 정규화 상수가 실수로 바뀌는 경우)를 잡는다.
"""
import numpy as np
import torch
from PIL import Image

from ai.core.unet_client import CRACK_INPUT_SIZE, predict_crack_probability


class _RecordingModel:
    """입력 텐서를 기록하고 고정된 logits를 반환하는 페이크 U-Net."""

    def __init__(self, output: torch.Tensor):
        self.received_input: torch.Tensor | None = None
        self._output = output

    def __call__(self, x: torch.Tensor) -> torch.Tensor:
        self.received_input = x
        return self._output


def test_predict_crack_probability_resizes_to_model_input_size_regardless_of_source_aspect_ratio():
    model = _RecordingModel(torch.zeros((1, 1, CRACK_INPUT_SIZE, CRACK_INPUT_SIZE)))
    image = Image.new("RGB", (1920, 1080), color=(0, 0, 0))  # 원본과 다른 종횡비

    predict_crack_probability(model, image)

    assert model.received_input.shape == (1, 3, CRACK_INPUT_SIZE, CRACK_INPUT_SIZE)


def test_predict_crack_probability_applies_imagenet_normalization_per_channel():
    model = _RecordingModel(torch.zeros((1, 1, CRACK_INPUT_SIZE, CRACK_INPUT_SIZE)))
    # 순수 빨간색 이미지 — 채널별 정규화 상수를 직접 역산해 검증한다(R=1.0, G=B=0.0 스케일 기준).
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
    model = _RecordingModel(torch.zeros((1, 1, CRACK_INPUT_SIZE, CRACK_INPUT_SIZE)))
    image = Image.new("RGB", (10, 10))

    predict_crack_probability(model, image)

    assert model.received_input.dtype == torch.float32


def test_predict_crack_probability_applies_sigmoid_and_squeezes_to_2d():
    model = _RecordingModel(torch.full((1, 1, CRACK_INPUT_SIZE, CRACK_INPUT_SIZE), 10.0))
    image = Image.new("RGB", (10, 10))

    probability = predict_crack_probability(model, image)

    assert probability.shape == (CRACK_INPUT_SIZE, CRACK_INPUT_SIZE)
    assert probability.min() >= 0.0
    assert probability.max() <= 1.0
    expected = 1.0 / (1.0 + np.exp(-10.0))
    assert np.allclose(probability, expected, atol=1e-4)
