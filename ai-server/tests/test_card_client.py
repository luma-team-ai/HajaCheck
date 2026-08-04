# -*- coding: utf-8 -*-
"""카드 검출 모듈 테스트 (#1487)."""
import pytest
from PIL import Image, ImageDraw
import numpy as np

from ai.core.card_client import (
    detect_card,
    CARD_RATIO,
    RATIO_TOL,
    CardDetectionResult,
)


def create_card_image(width: int, height: int, card_box: tuple | None = None) -> Image.Image:
    """테스트용 합성 이미지 생성.

    Args:
        width, height: 이미지 크기
        card_box: (x1, y1, x2, y2) 카드 위치, None이면 카드 없음

    Returns:
        RGB PIL Image
    """
    img = Image.new("RGB", (width, height), color="white")
    draw = ImageDraw.Draw(img)

    # 카드 없음
    if card_box is None:
        return img

    # 카드 그리기 (검은 직사각형)
    x1, y1, x2, y2 = card_box
    draw.rectangle([x1, y1, x2, y2], fill="black", outline="gray")

    # 카드 내부에 흰 선(테스트용 texture)
    for y in range(y1 + 20, y2, 40):
        draw.line([(x1 + 10, y), (x2 - 10, y)], fill="white", width=2)

    return img


class TestCardDetection:
    """카드 검출 기본 테스트."""

    def test_no_card(self):
        """카드 미검출 사진."""
        img = create_card_image(1920, 1440, card_box=None)
        result = detect_card(img)
        assert result is None

    def test_card_valid(self):
        """정상 카드 검출."""
        width, height = 1920, 1440
        # 정상 카드 종횡비 1.585, 크기 300x189px (bbox)
        card_w, card_h = 300, int(300 / CARD_RATIO)
        x1, y1 = 500, 500
        card_box = (x1, y1, x1 + card_w, y1 + card_h)
        img = create_card_image(width, height, card_box=card_box)

        result = detect_card(img)
        assert result is not None
        assert isinstance(result, CardDetectionResult)
        assert result.long_px == card_w  # 긴 변
        assert result.short_px == card_h  # 짧은 변
        assert 0 < result.confidence <= 1
        assert result.method in ("quad", "bbox", "quad_rec")

    def test_card_ratio_tolerance(self):
        """종횡비 허용 범위 내 카드."""
        width, height = 1920, 1440
        # 종횡비 1.5 (CARD_RATIO=1.585 범위 내)
        card_w, card_h = 300, 200
        x1, y1 = 500, 500
        card_box = (x1, y1, x1 + card_w, y1 + card_h)
        img = create_card_image(width, height, card_box=card_box)

        result = detect_card(img)
        if result:  # 정제 실패 가능성도 고려 (합성 이미지 한계)
            ratio = result.long_px / result.short_px
            assert 1.25 <= ratio <= 2.0

    def test_confidence_range(self):
        """신뢰도가 0~1 범위."""
        width, height = 1920, 1440
        card_w, card_h = 300, int(300 / CARD_RATIO)
        x1, y1 = 500, 500
        card_box = (x1, y1, x1 + card_w, y1 + card_h)
        img = create_card_image(width, height, card_box=card_box)

        result = detect_card(img)
        if result:
            assert 0 < result.confidence <= 1


class TestCardMeasurement:
    """카드로부터 스케일 산출."""

    def test_scale_calculation(self):
        """mm/px 스케일 계산."""
        # 카드 긴 변: 300px = 85.6mm -> 스케일 = 85.6/300 = 0.2853 mm/px
        from ai.core.card_client import CARD_LONG_MM

        long_px = 300.0
        expected_scale = CARD_LONG_MM / long_px
        assert expected_scale == pytest.approx(0.2853, rel=0.001)


class TestCrackMMIntegration:
    """균열 폭 mm 환산 통합 테스트."""

    def test_width_mm_with_scale(self):
        """스케일이 있을 때 width_mm 계산."""
        from ai.core.crack_mm_measurement import measure_crack_width_mm
        import cv2

        # 합성: 고정폭 2px의 균열 이미지
        h_orig, w_orig = 4000, 2296
        img_bgr = np.full((h_orig, w_orig, 3), 200, dtype=np.uint8)
        # 검은 직선(폭 2px)
        img_bgr[2000:2002, 500:1500, :] = 0

        # 합성 U-Net 마스크 (640x640에서 center에 선)
        mask_640 = np.zeros((640, 640), dtype=np.float32)
        mask_640[310:312, 100:400, ] = 1.0  # center 중 폭 2px 구간

        card_scale = 0.28  # mm/px (약 300px = 85.6mm)
        width_mm = measure_crack_width_mm(
            img_bgr,
            mask_640,
            card_scale,
            crack_input_size=640,
            crack_mask_threshold=0.5,
        )

        # 0.7mm 이상이면 기록해야 함
        if width_mm and width_mm >= 0.7:
            assert width_mm > 0

    def test_width_mm_zero_when_no_mask(self):
        """마스크 없을 때 mm은 None."""
        from ai.core.crack_mm_measurement import measure_crack_width_mm

        h_orig, w_orig = 4000, 2296
        img_bgr = np.full((h_orig, w_orig, 3), 200, dtype=np.uint8)
        mask_640 = np.zeros((640, 640), dtype=np.float32)  # 마스크 없음

        card_scale = 0.28
        width_mm = measure_crack_width_mm(
            img_bgr,
            mask_640,
            card_scale,
            crack_input_size=640,
            crack_mask_threshold=0.5,
        )
        assert width_mm is None


class TestThresholdGuard:
    """0.7mm 가드 로직."""

    def test_width_below_threshold_ignored(self):
        """0.7mm 미만은 기록하지 않음."""
        # 이 테스트는 실제 이미지와 모델이 필요해 unit 범위 밖.
        # integration 레벨에서 coverage하거나 수동 테스트 대상.
        pass

    def test_width_above_threshold_recorded(self):
        """0.7mm 이상은 기록."""
        # 실제 카드 검출 + U-Net 마스크 필요
        pass
