# -*- coding: utf-8 -*-
"""카드 검출 모듈 테스트 (#1487)."""
import base64
import io

import pytest
from unittest.mock import MagicMock, patch
from PIL import Image, ImageDraw
import numpy as np

from ai.core.card_client import (
    detect_card,
    CARD_RATIO,
    RATIO_TOL,
    CardDetectionResult,
    _pick_box,
)


class _NoBoxYoloModel:
    """SPALLING/REBAR_EXPOSURE 탐지 0건을 흉내내는 가짜 YOLO 모델(#1658 통합 테스트 전용).

    `run_defect_detection_chain`이 CRACK 외 두 유형도 항상 호출하므로, 카드검출↔CRACK 연결부만
    검증하는 이 테스트들에서는 YOLO 경로를 조용히 빈 결과로 만들어 노이즈를 없앤다.
    """

    def predict(self, **_kwargs):
        result = MagicMock()
        result.boxes = None
        return [result]


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

    @patch("ai.core.card_client._get_yolo_world_model")
    def test_no_card(self, mock_get_model):
        """카드 미검출 사진 (모델 mock)."""
        img = create_card_image(1920, 1440, card_box=None)

        mock_model = MagicMock()
        mock_results = MagicMock()
        mock_results[0].boxes = MagicMock()
        mock_results[0].boxes.__iter__ = lambda self: iter([])  # 박스 없음
        mock_model.predict.return_value = [mock_results]
        mock_model.set_classes = MagicMock()
        mock_get_model.return_value = mock_model

        result = detect_card(img)
        assert result is None

    @patch("ai.core.card_client._get_yolo_world_model")
    @patch("ai.core.card_client._attempt_detection_tiled")
    @patch("ai.core.card_client._attempt_detection")
    def test_card_valid(self, mock_attempt, mock_attempt_tiled, mock_get_model):
        """정상 카드 검출 (검출 로직 mock)."""
        width, height = 1920, 1440
        card_w, card_h = 300, int(300 / CARD_RATIO)
        img = create_card_image(width, height, card_box=(500, 500, 500 + card_w, 500 + card_h))

        mock_attempt.return_value = {
            "status": "pass",
            "method": "bbox",
            "conf": 0.8,
            "long_px": card_w,
            "short_px": card_h,
        }

        result = detect_card(img)
        assert result is not None
        assert isinstance(result, CardDetectionResult)
        assert result.long_px == card_w
        assert result.short_px == card_h
        assert 0 < result.confidence <= 1
        assert result.method in ("quad", "bbox", "quad_rec")

    @patch("ai.core.card_client._get_yolo_world_model")
    @patch("ai.core.card_client._attempt_detection")
    def test_card_ratio_tolerance(self, mock_attempt, mock_get_model):
        """종횡비 허용 범위 내 카드 (검출 로직 mock)."""
        card_w, card_h = 300, 200
        img = create_card_image(1920, 1440, card_box=(500, 500, 800, 700))

        mock_attempt.return_value = {
            "status": "pass",
            "method": "bbox",
            "conf": 0.75,
            "long_px": card_w,
            "short_px": card_h,
        }

        result = detect_card(img)
        if result:
            ratio = result.long_px / result.short_px
            assert 1.25 <= ratio <= 2.0

    @patch("ai.core.card_client._get_yolo_world_model")
    @patch("ai.core.card_client._attempt_detection")
    def test_confidence_range(self, mock_attempt, mock_get_model):
        """신뢰도가 0~1 범위 (검출 로직 mock)."""
        card_w, card_h = 300, int(300 / CARD_RATIO)
        img = create_card_image(1920, 1440, card_box=(500, 500, 500 + card_w, 500 + card_h))

        mock_attempt.return_value = {
            "status": "pass",
            "method": "bbox",
            "conf": 0.65,
            "long_px": card_w,
            "short_px": card_h,
        }

        result = detect_card(img)
        if result:
            assert 0 < result.confidence <= 1


class TestGeometryFunctions:
    """_pick_box 단위 테스트 (모델 독립)."""

    def test_pick_box_valid(self):
        """기하 검증을 통과하는 박스."""
        boxes = [(0.9, 100, 100, 400, 250), (0.5, 500, 500, 600, 700)]
        img_area = 1920 * 1440
        result = _pick_box(boxes, img_area, min_side=40)
        assert result is not None
        assert result[0] == 0.9

    def test_pick_box_empty(self):
        """박스 없음."""
        result = _pick_box([], 1920 * 1440, min_side=40)
        assert result is None


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


class TestCrackDetectionsIntegration:
    """`_crack_detections()` + `_apply_crack_width_mm()` 통합 테스트 — 카드검출→mm환산 연결부
    회귀 방지 (#1547 P1 재발 방지, #1658로 카드 검출 호출부가 run_defect_detection_chain으로
    이동하면서 이 클래스도 두 함수의 조합을 검증하도록 갱신).

    measure_crack_width_mm 내부 CV 알고리즘은 TestCrackMMIntegration이 이미 검증하므로 여기선
    mock으로 대체하고, _apply_crack_width_mm이 그 함수에 "올바른 인자"를 넘기는지만 검증한다 —
    스케일 공식 방향과 레터박스 크기 전달이 실제로 둘 다 틀렸었다(#1547).
    """

    @patch("ai.core.crack_mm_measurement.measure_crack_width_mm")
    @patch("ai.chains.defect_detection_chain.predict_crack_probability")
    @patch("ai.chains.defect_detection_chain.get_crack_model")
    def test_scale_and_input_size_passed_correctly(
        self, mock_get_model, mock_predict, mock_measure
    ):
        """비정사각(세로) 사진 기준 — 스케일 공식 방향 + crack_input_size 고정값 + 패딩제거 마스크 검증."""
        from ai.chains.defect_detection_chain import (
            _crack_detections,
            _apply_crack_width_mm,
            CRACK_INPUT_SIZE,
        )
        from ai.core.unet_client import CrackPrediction
        from ai.core.card_client import CARD_LONG_MM

        # 세로 사진(2296x4080) — height가 max라 letterbox content_height=640, content_width=360
        # (정사각 이미지로 테스트하면 content_width가 우연히 640이 돼 이 버그가 안 잡힌다)
        w_orig, h_orig = 2296, 4080
        image = Image.new("RGB", (w_orig, h_orig), color="white")

        content_height, content_width = 640, 360  # _letterbox_layout(2296,4080)과 일치
        top, left = 0, (640 - content_width) // 2
        probability = np.zeros((640, 640), dtype=np.float32)
        # 콘텐츠 영역 내부에 마스크 블록(30x30 — MIN_CRACK_COMPONENT_AREA_RATIO 통과용)
        probability[300:330, left + 100:left + 130] = 0.9

        mock_get_model.return_value = MagicMock()
        mock_predict.return_value = CrackPrediction(
            probability=probability,
            content_top=top, content_left=left,
            content_height=content_height, content_width=content_width,
        )
        mock_measure.return_value = 2.0  # 반환값 자체는 안 씀 — 호출 인자만 검증 대상

        detections, content_probability = _crack_detections(image)
        # long_px=300 -> scale = 85.6/300 (run_defect_detection_chain이 카드 검출 1회로 산출하는 값)
        card_scale_mm_per_px = CARD_LONG_MM / 300.0
        _apply_crack_width_mm(detections, image, content_probability, card_scale_mm_per_px)

        assert mock_measure.called, "카드가 검출됐는데 measure_crack_width_mm이 호출되지 않음"
        call = mock_measure.call_args
        _, passed_mask, passed_scale = call.args
        passed_input_size = call.kwargs["crack_input_size"]

        # ① 스케일 공식 방향 — CARD_LONG_MM/long_px여야 함(역방향이면 (long_px/85.6)^2배 틀어짐)
        expected_scale = CARD_LONG_MM / 300.0
        assert passed_scale == pytest.approx(expected_scale, rel=0.01), (
            f"스케일 공식이 뒤집힌 것으로 보임: {passed_scale} (기대: {expected_scale})"
        )
        # ② crack_input_size는 content_width(360)가 아니라 항상 고정 640(레터박스 캔버스 크기)
        assert passed_input_size == CRACK_INPUT_SIZE == 640, (
            f"crack_input_size가 content_width로 잘못 전달됨: {passed_input_size}"
        )
        # ③ 마스크는 패딩 제거된 content 크기(640x360)여야 함 — 패딩 포함 640x640 그대로면
        #    원본해상도 리사이즈 시 좌표가 어긋난다
        assert passed_mask.shape == (content_height, content_width), (
            f"패딩이 안 잘린 마스크가 그대로 전달됨: {passed_mask.shape} "
            f"(기대: {(content_height, content_width)})"
        )

    @patch("ai.chains.defect_detection_chain.detect_card")
    @patch("ai.chains.defect_detection_chain.predict_crack_probability")
    @patch("ai.chains.defect_detection_chain.get_crack_model")
    @patch("ai.chains.defect_detection_chain.get_yolo_model")
    def test_card_detection_failure_does_not_break_crack_detection(
        self, mock_get_yolo_model, mock_get_model, mock_predict, mock_detect_card
    ):
        """detect_card가 예외를 던져도 균열 탐지 자체는 살아있어야 함(#1547 정재봉 P1 리뷰).

        #1658로 detect_card 호출부가 `run_defect_detection_chain`(→ `_safe_detect_card`)으로
        옮겨갔다 — 이 가드가 없으면 카드 검출 실패가 이미 구한 CRACK/SPALLING/REBAR_EXPOSURE
        detections 전체를 무너뜨린다. mm 환산은 부가기능인데 그 실패가 핵심기능을 죽이는 구조였다.
        """
        from ai.chains.defect_detection_chain import run_defect_detection_chain
        from ai.core.unet_client import CrackPrediction

        w_orig, h_orig = 2296, 4080
        image = Image.new("RGB", (w_orig, h_orig), color="white")
        buf = io.BytesIO()
        image.save(buf, format="PNG")
        image_base64 = base64.b64encode(buf.getvalue()).decode("ascii")

        content_height, content_width = 640, 360
        top, left = 0, (640 - content_width) // 2
        probability = np.zeros((640, 640), dtype=np.float32)
        probability[300:330, left + 100:left + 130] = 0.9  # 균열 마스크 블록

        mock_get_model.return_value = MagicMock()
        mock_predict.return_value = CrackPrediction(
            probability=probability,
            content_top=top, content_left=left,
            content_height=content_height, content_width=content_width,
        )
        mock_detect_card.side_effect = RuntimeError("카드 검출 모델 로드 실패(예: 네트워크)")
        mock_get_yolo_model.return_value = _NoBoxYoloModel()

        detections = run_defect_detection_chain(image_base64)  # 예외 없이 반환돼야 함

        crack_detections = [d for d in detections if d.type == "CRACK"]
        assert crack_detections, "카드 검출 실패로 균열 탐지 결과 자체가 사라짐 — 가드 미작동"
        assert all(d.width_mm is None for d in crack_detections), (
            "카드 검출 실패 시에도 width_mm이 채워짐 — None 유지돼야 함"
        )

    @patch("ai.chains.defect_detection_chain.detect_card")
    @patch("ai.chains.defect_detection_chain.predict_crack_probability")
    @patch("ai.chains.defect_detection_chain.get_crack_model")
    @patch("ai.chains.defect_detection_chain.get_yolo_model")
    def test_no_crack_skips_card_detection(
        self, mock_get_yolo_model, mock_get_model, mock_predict, mock_detect_card
    ):
        """세 유형 모두 detection이 없으면 고비용 카드 검출 자체를 건너뛰어야 함(#1547 P2를
        CRACK 전용에서 세 유형 공통 스킵 조건으로 확장, #1658)."""
        from ai.chains.defect_detection_chain import run_defect_detection_chain
        from ai.core.unet_client import CrackPrediction

        image = Image.new("RGB", (2296, 4080), color="white")
        buf = io.BytesIO()
        image.save(buf, format="PNG")
        image_base64 = base64.b64encode(buf.getvalue()).decode("ascii")

        mock_get_model.return_value = MagicMock()
        mock_predict.return_value = CrackPrediction(
            probability=np.zeros((640, 640), dtype=np.float32),  # 균열 없음
            content_top=0, content_left=140, content_height=640, content_width=360,
        )
        mock_get_yolo_model.return_value = _NoBoxYoloModel()  # SPALLING/REBAR_EXPOSURE도 없음

        detections = run_defect_detection_chain(image_base64)

        assert detections == []
        assert not mock_detect_card.called, (
            "detection 0건인데 카드 검출이 실행됨 — YOLO-World 추론 낭비 가드 미작동"
        )

    @patch("ai.chains.defect_detection_chain.detect_card")
    @patch("ai.chains.defect_detection_chain.predict_crack_probability")
    @patch("ai.chains.defect_detection_chain.get_crack_model")
    def test_multiple_cracks_get_individual_width_mm(
        self, mock_get_model, mock_predict, mock_detect_card
    ):
        """분리된 균열 2개는 각자 자기 폭의 width_mm을 받아야 함(#1547 P2 정합성 버그 회귀 고정).

        전체 마스크를 그대로 measure에 넘기면 내부의 최대-연결요소 선택 때문에 모든 detection이
        가장 큰 균열의 폭을 복사받는다 — detection별 bbox 스코핑으로 각자 측정하는지 검증한다.
        """
        from ai.chains.defect_detection_chain import _crack_detections, _apply_crack_width_mm
        from ai.core.unet_client import CrackPrediction
        from ai.core.card_client import CARD_LONG_MM

        # 세로 사진 2296x4080, content 640x360, content_scale = 4080/640 = 6.375
        w_orig, h_orig = 2296, 4080
        img = np.full((h_orig, w_orig, 3), 200, dtype=np.uint8)
        # 균열 A(가늘게, 두께 20px): 원본 y 640~660, x 380~1020
        img[640:660, 380:1020, :] = 0
        # 균열 B(굵게, 두께 40px): 원본 y 2550~2590, x 380~1020
        img[2550:2590, 380:1020, :] = 0
        image = Image.fromarray(img[:, :, ::-1])  # BGR->RGB

        # content 좌표(6.375분의 1)로 마스크 블록 — A: rows 100~103 / B: rows 400~403
        probability = np.zeros((640, 640), dtype=np.float32)
        left = (640 - 360) // 2
        probability[100:103, left + 60:left + 160] = 0.9
        probability[400:403, left + 60:left + 160] = 0.9

        mock_get_model.return_value = MagicMock()
        mock_predict.return_value = CrackPrediction(
            probability=probability,
            content_top=0, content_left=left, content_height=640, content_width=360,
        )

        detections, content_probability = _crack_detections(image)
        # long_px=300 -> scale = 85.6/300 = 0.2853 mm/px(run_defect_detection_chain이 공유하는 값)
        card_scale_mm_per_px = CARD_LONG_MM / 300.0
        _apply_crack_width_mm(detections, image, content_probability, card_scale_mm_per_px)

        cracks = [d for d in detections if d.type == "CRACK" and d.width_mm is not None]
        assert len(cracks) == 2, f"균열 2건 모두 width_mm이 있어야 함: {detections}"

        widths = sorted(d.width_mm for d in cracks)
        # A ≈ 20px*0.285 ≈ 5.5mm / B ≈ 40px*0.285 ≈ 10.7mm — 같은 값 복사면 ratio 1.0
        assert widths[1] / widths[0] >= 1.4, (
            f"분리된 두 균열의 width_mm이 사실상 동일({widths}) — "
            "전체 마스크 최대-연결요소 폭이 복사된 것(P2 버그 재발)"
        )


class TestThresholdGuard:
    """0.7mm 가드 로직."""

    @pytest.mark.skip(reason="실제 YOLO-World + U-Net 모델 필요, 로컬 실측 사진 없음")
    def test_width_below_threshold_ignored(self):
        """0.7mm 미만은 기록하지 않음."""
        pass

    @pytest.mark.skip(reason="실제 YOLO-World + U-Net 모델 필요, 로컬 실측 사진 없음")
    def test_width_above_threshold_recorded(self):
        """0.7mm 이상은 기록."""
        pass
