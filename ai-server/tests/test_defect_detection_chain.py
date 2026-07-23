"""ai.chains.defect_detection_chain._decode_image 단위 테스트(코드 리뷰 P2 — decompression bomb 방어).

base64 길이 상한만으로는 고압축 이미지(단색에 가까운 대형 PNG 등)가 작은 페이로드로 거대한 픽셀
버퍼로 디코딩되는 걸 막지 못한다. PIL.Image.open을 몽키패치해 "헤더는 거대한 해상도를 선언하지만
실제 픽셀 버퍼는 할당하지 않는" 상황을 값싸게 재현한다 — 진짜 폭탄 이미지를 테스트에서 만들면
테스트 자체가 느려지거나 메모리를 많이 먹는다.
"""
import base64
import io

import pytest
from PIL import Image

from ai.chains.defect_detection_chain import MAX_IMAGE_PIXELS, DefectDetectionError, _decode_image


def _tiny_valid_png_base64() -> str:
    buf = io.BytesIO()
    Image.new("RGB", (2, 2), color=(255, 0, 0)).save(buf, format="PNG")
    return base64.b64encode(buf.getvalue()).decode("ascii")


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
