"""AI 하자 탐지 체인(dev-05-04, PRD §FR-3) — YOLOv8-seg 추론 -> 클래스 정규화 -> 등급 산정.

business_license_ocr_chain.py와 동일한 구조(디코드 -> 모델 호출 -> 후처리 -> 결과 객체)를 따르되
LLM이 아니라 `ai/core/yolo_client.py`(ultralytics)를 호출한다. 등급 산정은
docs/conventions/하자_심각도_등급_규칙.md §4 권장대로 이 단계(FastAPI 탐지 후처리)에서 수행해
`grade`를 결과 payload에 포함한다 — Spring은 저장만 하고 재계산하지 않는다.

3종 확정 클래스(균열/박리·박락/철근노출) 밖의 탐지(모델이 실험적으로 더 넓게 학습됐을 가능성 대비)는
조용히 건너뛴다 — 매핑 불가 라벨을 DefectType 미상 값으로 저장하면 Spring 쪽 enum 매핑이 깨진다.
"""
from __future__ import annotations

import base64
import binascii
import io
from typing import TYPE_CHECKING

from pydantic import BaseModel

from ai.core.grading import compute_grade, normalize_defect_type_label
from ai.core.yolo_client import get_yolo_model

if TYPE_CHECKING:
    from PIL import Image

# 점검 미디어 업로드 상한 20MB(MediaUploadProperties, backend)의 base64 상당치(+33% 여유 포함).
MAX_IMAGE_BASE64_LENGTH = 28_000_000

# YOLO 추론 자체가 임계값을 너무 낮게 잡으면 잡음(false positive)이 쏟아진다 — 1차 보수적 기본값.
DEFAULT_CONFIDENCE_THRESHOLD = 0.25


class DetectedDefect(BaseModel):
    """탐지 1건 — Spring DefectDetectionAiEnvelope.data 항목과 1:1 대응(필드명 camelCase는
    Spring 쪽 Jackson 매핑 관례를 따라 여기서는 snake_case 그대로 두고 Envelope DTO에서 매핑)."""

    type: str  # DefectType enum 이름(CRACK/SPALLING/REBAR_EXPOSURE)
    bbox_x: float
    bbox_y: float
    bbox_w: float
    bbox_h: float
    confidence: float
    grade: str  # A~E


class DefectDetectionError(Exception):
    """디코딩/추론 실패 — 원인 메시지에 내부 경로·모델 상세가 담기지 않도록 고정 문구만 사용."""


def _decode_image(image_base64: str) -> "Image.Image":
    # ultralytics model.predict(source=...)는 raw bytes를 받지 않는다(check_source가 지원 타입
    # 목록에서 bytes를 거부 — easyocr.readtext()와 달리 자체적으로 cv2.imdecode하지 않음).
    # PIL Image로 디코딩해 넘긴다(ultralytics 공식 지원 타입).
    from PIL import Image, UnidentifiedImageError

    if len(image_base64) > MAX_IMAGE_BASE64_LENGTH:
        raise DefectDetectionError("이미지 크기가 허용 상한(20MB)을 초과했습니다")
    try:
        raw = base64.b64decode(image_base64, validate=True)
    except (binascii.Error, ValueError) as e:
        raise DefectDetectionError("image_base64가 올바른 base64 인코딩이 아닙니다") from e
    try:
        return Image.open(io.BytesIO(raw)).convert("RGB")
    except UnidentifiedImageError as e:
        raise DefectDetectionError("image_base64가 올바른 이미지 파일이 아닙니다") from e


def _mask_area_ratio(masks, index: int, fallback_bbox_area: float) -> float:
    """세그멘테이션 마스크가 있으면 (마스크 픽셀 수 / 전체 픽셀 수), 없으면(비-seg 체크포인트 등
    예외적 상황) 바운딩박스 면적을 근사치로 사용한다(§3.1 프록시 원칙의 최선 근사)."""
    if masks is None:
        return fallback_bbox_area
    mask = masks.data[index]
    return float(mask.sum()) / float(mask.numel())


def run_defect_detection_chain(image_base64: str) -> list[DetectedDefect]:
    image = _decode_image(image_base64)

    model = get_yolo_model()
    results = model.predict(source=image, conf=DEFAULT_CONFIDENCE_THRESHOLD, verbose=False)
    result = results[0]

    names = result.names or model.names
    boxes = result.boxes
    if boxes is None or len(boxes) == 0:
        return []

    detections: list[DetectedDefect] = []
    xyxyn = boxes.xyxyn.tolist()
    confidences = boxes.conf.tolist()
    class_ids = boxes.cls.tolist()

    for i, (x1, y1, x2, y2) in enumerate(xyxyn):
        raw_label = names.get(int(class_ids[i]), "")
        defect_type = normalize_defect_type_label(raw_label)
        if defect_type is None:
            # 3종 확정 클래스 밖의 라벨 — 조용히 건너뛴다(모듈 docstring 참고).
            continue

        bbox_w, bbox_h = x2 - x1, y2 - y1
        area_ratio = _mask_area_ratio(result.masks, i, fallback_bbox_area=bbox_w * bbox_h)
        grade = compute_grade(defect_type, area_ratio)

        detections.append(
            DetectedDefect(
                type=defect_type,
                bbox_x=x1,
                bbox_y=y1,
                bbox_w=bbox_w,
                bbox_h=bbox_h,
                confidence=round(float(confidences[i]), 4),
                grade=grade,
            )
        )

    return detections
