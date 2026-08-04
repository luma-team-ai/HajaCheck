# -*- coding: utf-8 -*-
"""신용카드 등 기준물(85.6×54mm) 자동검출 + mm/px 스케일 산출 (촬영규약 docs/design/crack-measurement-protocol.md).

YOLO-World 제로샷 검출("card", "credit card", "sticker on wall" 프롬프트)로 후보를 찾고,
기하 검증(종횡비, 크기)과 정제(Otsu+Canny 이진화 -> minAreaRect 검증)를 통해 정확한 카드 경계를
추정한다. 2x2 타일(+15% 오버랩)로 재검출 패스를 갖춰 원거리 극소 카드도 처리한다.

검증(로컬 실측 102장): 카드검출 82% 성공률, 정상 정제 92%.
"""
from __future__ import annotations

from functools import lru_cache
from typing import NamedTuple, TYPE_CHECKING

if TYPE_CHECKING:
    import numpy as np
    from PIL import Image


class CardDetectionResult(NamedTuple):
    """카드 검출 성공 시 반환값.

    Attributes:
        long_px: 카드 긴 변(픽셀, 원본 이미지 좌표)
        short_px: 카드 짧은 변(픽셀, 원본 이미지 좌표)
        confidence: YOLO 신뢰도(0~1)
        method: 검출 방식("quad"=정밀 정제, "bbox"=bounding box 직사용, "quad_rec"=회수 정제)
    """
    long_px: float
    short_px: float
    confidence: float
    method: str


# 카드 규격(ISO 7810 ID-1) 및 검증 임계값
CARD_LONG_MM = 85.6
CARD_SHORT_MM = 54.0
CARD_RATIO = CARD_LONG_MM / CARD_SHORT_MM  # 1.585
RATIO_TOL = 0.12  # ±12% — 정면 촬영 ±10° 원근 왜곡 허용

# YOLO-World 설정
YOLO_WORLD_MODEL_NAME = "yolov8s-worldv2.pt"  # ultralytics 공식 릴리스에서 자동 다운로드
YOLO_WORLD_PROMPTS = ["card", "credit card", "sticker on wall"]
YOLO_WORLD_IMGSZ = 2048  # 고해상도 입력으로 소형 카드까지 처리 가능하게 함
YOLO_WORLD_CONF_MIN = 0.02  # 제로샷이라 threshold 낮게 — 기하 검증으로 거름

# 카드 정제 및 회수(타일링) 설정
NORM_BAND = (0.75, 1.15)  # 정상 정제: 긴변이 bbox 긴변의 이 범위여야 함
RELAX_BAND = (0.60, 1.15)  # 회수 정제: bbox가 부푼 경우 카드 본체만 재선택


def detect_card(image: Image.Image) -> CardDetectionResult | None:
    """이미지 속 기준물(카드) 자동검출.

    Args:
        image: PIL Image (RGB)

    Returns:
        CardDetectionResult(long_px, short_px, confidence, method) 또는 None(미검출)
    """
    import cv2
    import numpy as np

    img_cv = cv2.cvtColor(np.array(image), cv2.COLOR_RGB2BGR)
    h_img, w_img = img_cv.shape[:2]
    img_area = h_img * w_img

    model = _get_yolo_world_model()

    # 1. 전체 이미지에서 검출 시도
    result = _attempt_detection(model, img_cv, img_area, YOLO_WORLD_CONF_MIN, min_side=40)
    if result and result["status"] == "pass":
        return CardDetectionResult(
            long_px=result["long_px"],
            short_px=result["short_px"],
            confidence=result["conf"],
            method=result["method"],
        )

    # 2. 타일링 재시도 (탐지 실패 또는 종횡비 오류인 경우만)
    if result is None or result.get("status") != "pass":
        result_tiled = _attempt_detection_tiled(model, img_cv, img_area, YOLO_WORLD_CONF_MIN, min_side=28)
        if result_tiled and result_tiled["status"] == "pass":
            return CardDetectionResult(
                long_px=result_tiled["long_px"],
                short_px=result_tiled["short_px"],
                confidence=result_tiled["conf"],
                method=result_tiled["method"],
            )

    return None


@lru_cache(maxsize=1)
def _get_yolo_world_model():
    """YOLO-World 모델 로더(프로세스당 1회만 로드, lru_cache)."""
    from ultralytics import YOLOWorld

    model = YOLOWorld(YOLO_WORLD_MODEL_NAME)
    model.set_classes(YOLO_WORLD_PROMPTS)
    return model


def _attempt_detection(model, img: "np.ndarray", img_area: float, conf_min: float, min_side: float) -> dict | None:
    """YOLO-World로 카드 검출 시도."""
    import cv2
    import numpy as np

    results = model.predict(img, conf=conf_min, imgsz=YOLO_WORLD_IMGSZ, verbose=False)
    boxes_data = [(float(b.conf), *map(int, b.xyxy[0].tolist())) for b in results[0].boxes]

    if not boxes_data:
        return None

    picked = _pick_box(boxes_data, img_area, min_side)
    if picked is None:
        return None

    conf, x1, y1, x2, y2 = picked
    bbox_w, bbox_h = x2 - x1, y2 - y1
    bbox_long = max(bbox_w, bbox_h)

    margin_w = int(bbox_w * 0.12)
    margin_h = int(bbox_h * 0.12)
    rx1, ry1 = max(0, x1 - margin_w), max(0, y1 - margin_h)
    rx2, ry2 = min(img.shape[1], x2 + margin_w), min(img.shape[0], y2 + margin_h)
    roi = img[ry1:ry2, rx1:rx2]

    # 정상 정제
    refined = _refine_quad(roi, bbox_long, NORM_BAND)
    if refined:
        _, long_, short_ = refined
        return {"status": "pass", "method": "quad", "conf": conf, "long_px": long_, "short_px": short_}

    # bbox 폴백
    ratio = bbox_long / min(bbox_w, bbox_h)
    if abs(ratio - CARD_RATIO) / CARD_RATIO <= RATIO_TOL:
        return {"status": "pass", "method": "bbox", "conf": conf, "long_px": bbox_long, "short_px": min(bbox_w, bbox_h)}

    # 회수 정제
    refined = _refine_quad(roi, bbox_long, RELAX_BAND)
    if refined and refined[1] * refined[2] >= 0.5 * bbox_w * bbox_h:
        _, long_, short_ = refined
        return {"status": "pass", "method": "quad_rec", "conf": conf, "long_px": long_, "short_px": short_}

    return {"status": "ratio_fail", "method": "bbox", "conf": conf, "long_px": bbox_long, "short_px": min(bbox_w, bbox_h)}


def _attempt_detection_tiled(model, img: "np.ndarray", img_area: float, conf_min: float, min_side: float) -> dict | None:
    """2x2 타일(+15% 오버랩)로 재검출."""
    import cv2
    import numpy as np

    h_img, w_img = img.shape[:2]
    all_boxes = []
    tile_h, tile_w = int(h_img * 0.575), int(w_img * 0.575)

    for oy in [0, int(h_img * 0.425)]:
        for ox in [0, int(w_img * 0.425)]:
            tile = img[oy:min(oy + tile_h, h_img), ox:min(ox + tile_w, w_img)]
            results = model.predict(tile, conf=conf_min, imgsz=YOLO_WORLD_IMGSZ, verbose=False)
            for b in results[0].boxes:
                x1, y1, x2, y2 = map(int, b.xyxy[0].tolist())
                all_boxes.append((float(b.conf), x1 + ox, y1 + oy, x2 + ox, y2 + oy))

    if not all_boxes:
        return None

    picked = _pick_box(all_boxes, img_area, min_side)
    if picked is None:
        return None

    conf, x1, y1, x2, y2 = picked
    bbox_w, bbox_h = x2 - x1, y2 - y1
    bbox_long = max(bbox_w, bbox_h)

    margin_w, margin_h = int(bbox_w * 0.12), int(bbox_h * 0.12)
    rx1, ry1 = max(0, x1 - margin_w), max(0, y1 - margin_h)
    rx2, ry2 = min(img.shape[1], x2 + margin_w), min(img.shape[0], y2 + margin_h)
    roi = img[ry1:ry2, rx1:rx2]

    refined = _refine_quad(roi, bbox_long, NORM_BAND)
    if refined:
        _, long_, short_ = refined
        return {"status": "pass", "method": "quad", "conf": conf, "long_px": long_, "short_px": short_}

    ratio = bbox_long / min(bbox_w, bbox_h)
    if abs(ratio - CARD_RATIO) / CARD_RATIO <= RATIO_TOL:
        return {"status": "pass", "method": "bbox", "conf": conf, "long_px": bbox_long, "short_px": min(bbox_w, bbox_h)}

    refined = _refine_quad(roi, bbox_long, RELAX_BAND)
    if refined and refined[1] * refined[2] >= 0.5 * bbox_w * bbox_h:
        _, long_, short_ = refined
        return {"status": "pass", "method": "quad_rec", "conf": conf, "long_px": long_, "short_px": short_}

    return None


def _pick_box(boxes: list, img_area: float, min_side: float) -> tuple | None:
    """기하 검증을 통과하는 첫 박스 반환."""
    for conf, x1, y1, x2, y2 in sorted(boxes, key=lambda t: -t[0]):
        bw, bh = x2 - x1, y2 - y1
        if min(bw, bh) < min_side or bw * bh > 0.2 * img_area:
            continue
        r = max(bw, bh) / min(bw, bh)
        if 1.25 <= r <= 2.0:
            return (conf, x1, y1, x2, y2)
    return None


def _refine_quad(roi: "np.ndarray", bbox_long: float, band: tuple) -> tuple | None:
    """ROI에서 카드 사각형을 정밀하게 정제."""
    import cv2
    import numpy as np

    gray = cv2.cvtColor(roi, cv2.COLOR_BGR2GRAY)
    gray = cv2.GaussianBlur(gray, (5, 5), 0)
    roi_area = roi.shape[0] * roi.shape[1]

    binaries = []
    _, otsu = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    binaries.extend([otsu, cv2.bitwise_not(otsu)])
    edges = cv2.Canny(gray, 40, 120)
    edges = cv2.morphologyEx(cv2.dilate(edges, np.ones((3, 3), np.uint8)), cv2.MORPH_CLOSE, np.ones((7, 7), np.uint8))
    binaries.append(edges)

    candidates = []
    for b in binaries:
        cnts, _ = cv2.findContours(b, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
        for c in cnts:
            area = cv2.contourArea(c)
            if area < 0.05 * roi_area:
                continue
            rect = cv2.minAreaRect(c)
            w, h = rect[1]
            if min(w, h) < 10 or w * h > 0.80 * roi_area:
                continue
            long_, short_ = max(w, h), min(w, h)
            if long_ < 50 or not (band[0] * bbox_long <= long_ <= band[1] * bbox_long):
                continue
            ratio = long_ / short_
            rectangularity = area / (w * h)
            if abs(ratio - CARD_RATIO) / CARD_RATIO <= RATIO_TOL and rectangularity > 0.80:
                candidates.append((area, rect))

    if not candidates:
        return None
    _, rect = max(candidates, key=lambda t: t[0])
    w, h = rect[1]
    return None, max(w, h), min(w, h)
