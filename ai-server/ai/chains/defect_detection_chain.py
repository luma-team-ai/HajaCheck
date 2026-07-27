"""AI 하자 탐지 체인(dev-05-04, PRD §FR-3) — 유형별 전용 모델 추론 -> 인스턴스화 -> 등급 산정.

business_license_ocr_chain.py와 동일한 구조(디코드 -> 모델 호출 -> 후처리 -> 결과 객체)를 따르되
LLM이 아니라 `ai/core/yolo_client.py`(ultralytics)·`ai/core/unet_client.py`(U-Net)를 호출한다.
등급 산정은 docs/conventions/하자_심각도_등급_규칙.md §4 권장대로 이 단계(FastAPI 탐지 후처리)에서
수행해 `grade`를 결과 payload에 포함한다 — Spring은 저장만 하고 재계산하지 않는다.

## 유형별 전용 모델 3개를 각각 호출한다 (2026-07-27, 6차 rebase 중 재설계)

원래는 모델 1개가 3클래스(균열/박리박락/철근노출)를 전부 처리하고, 반환된 클래스 라벨을
`grading.normalize_defect_type_label`로 정규화해 유형을 판별했다. 그런데 HF Hub 저장소가 유형별
전용 체크포인트 구조로 바뀌면서(yolo_client.py 모듈 docstring 참고) 이 전제가 깨졌다 — 특히
`rebar_exposure_yolov8n_seg.pt`의 내부 클래스는 `good/fair/poor`(상태 등급)라 라벨 텍스트로는
유형을 되짚을 수 없다. 그래서 **어떤 모델을 호출했는지 자체로 유형을 고정**한다: 균열은
`_crack_detections`(U-Net, 연결요소 분석), 박리박락·철근노출은 `_yolo_type_detections`(유형 전용
YOLO 체크포인트)를 통해 각각 독립적으로 탐지하고 결과를 이미지 1장당 합친다.
"""
from __future__ import annotations

import base64
import binascii
import io
from typing import TYPE_CHECKING

from pydantic import BaseModel

from ai.core.grading import compute_grade
from ai.core.unet_client import (
    CRACK_MASK_THRESHOLD,
    get_crack_model,
    predict_crack_probability,
)
from ai.core.yolo_client import get_yolo_model
from ai.core.yolo_client import predict as yolo_predict

if TYPE_CHECKING:
    import numpy as np
    from PIL import Image

# 점검 미디어 업로드 상한 20MB(MediaUploadProperties, backend)의 base64 상당치(+33% 여유 포함).
MAX_IMAGE_BASE64_LENGTH = 28_000_000

# decompression bomb 방어(코드 리뷰 P2) — base64 길이 상한만으로는 안 막힌다. 고압축 포맷(PNG 등)은
# 단색에 가까운 대형 이미지를 수 KB로 압축하므로, 작은 base64가 수천만~수억 픽셀로 디코딩될 수
# 있다. business_license_ocr_chain._decode_image는 bytes를 그대로 easyocr에 넘겨(cv2.imdecode가
# 내부 처리) 이 문제가 없지만, 여긴 PIL.Image.open + convert("RGB")로 전체 픽셀 버퍼를 직접
# 할당하므로 별도 방어가 필요하다. 40MP는 실제 현장 촬영 사진의 현실적 해상도 상한(고화소
# DSLR/드론 기준)보다 넉넉히 잡은 값 — 정상 사용은 막지 않으면서 폭탄만 차단한다.
MAX_IMAGE_PIXELS = 40_000_000

# YOLO 추론 자체가 임계값을 너무 낮게 잡으면 잡음(false positive)이 쏟아진다 — 1차 보수적 기본값.
DEFAULT_CONFIDENCE_THRESHOLD = 0.25

# U-Net 마스크의 연결요소 중 이 픽셀 수 미만은 노이즈 스펙클로 간주해 버린다(입력이 항상
# unet_client.CRACK_INPUT_SIZE=640 정사각형으로 고정되므로 절대 픽셀 수 기준으로 충분하다).
MIN_CRACK_COMPONENT_PIXELS = 20


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
    area_ratio: float  # 세그멘테이션 마스크 기반 면적비율(또는 바운딩박스 근사치)


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
        image = Image.open(io.BytesIO(raw))
    except UnidentifiedImageError as e:
        raise DefectDetectionError("image_base64가 올바른 이미지 파일이 아닙니다") from e

    # Image.open()은 헤더만 읽고 지연 디코딩한다(size 접근은 픽셀 버퍼를 할당하지 않음) — 그래서
    # 픽셀 상한 검사를 convert() 앞에 둬야 실제 전체 버퍼 할당(비용이 큰 부분) 전에 거부할 수 있다.
    width, height = image.size
    if width * height > MAX_IMAGE_PIXELS:
        raise DefectDetectionError("이미지 해상도가 허용 상한을 초과했습니다")
    return image.convert("RGB")


def _mask_area_ratio(masks, index: int, fallback_bbox_area: float) -> float:
    """세그멘테이션 마스크가 있으면 (마스크 픽셀 수 / 전체 픽셀 수), 없으면(비-seg 체크포인트 등
    예외적 상황) 바운딩박스 면적을 근사치로 사용한다(§3.1 프록시 원칙의 최선 근사)."""
    if masks is None:
        return fallback_bbox_area
    mask = masks.data[index]
    return float(mask.sum()) / float(mask.numel())


def _crack_mask_to_detections(mask: "np.ndarray", probability: "np.ndarray") -> list[DetectedDefect]:
    """균열 확률 맵 -> 연결요소별 DetectedDefect 목록.

    U-Net은 박스·인스턴스·신뢰도가 없는 픽셀 단위 마스크만 낸다 — cv2.connectedComponentsWithStats로
    분리된 균열 영역 하나하나를 인스턴스처럼 취급해 바운딩박스를 역산한다. area_ratio는 기존 YOLO
    경로(_mask_area_ratio)와 동일한 의미(인스턴스 마스크 픽셀 수 / 이미지 전체 픽셀 수)를 유지해
    grading.py가 두 경로를 구분 없이 처리할 수 있게 한다. confidence는 인스턴스 단위 점수가
    없으므로 해당 연결요소 내부 픽셀들의 평균 확률(threshold 이전 원 확률)로 근사한다.
    """
    import cv2

    mask_u8 = mask.astype("uint8")
    num_labels, labels, stats, _ = cv2.connectedComponentsWithStats(mask_u8, connectivity=8)
    total_pixels = mask.size
    height, width = mask.shape

    detections: list[DetectedDefect] = []
    for label in range(1, num_labels):  # 0번 라벨=배경
        x, y, w, h, area = stats[label]
        if area < MIN_CRACK_COMPONENT_PIXELS:
            continue
        area_ratio = float(area) / float(total_pixels)
        confidence = float(probability[labels == label].mean())
        grade = compute_grade("CRACK", area_ratio)

        detections.append(
            DetectedDefect(
                type="CRACK",
                bbox_x=x / width,
                bbox_y=y / height,
                bbox_w=w / width,
                bbox_h=h / height,
                confidence=round(confidence, 4),
                grade=grade,
                area_ratio=area_ratio,
            )
        )
    return detections


def _crack_detections(image: "Image.Image") -> list[DetectedDefect]:
    model = get_crack_model()
    probability = predict_crack_probability(model, image)
    mask = probability > CRACK_MASK_THRESHOLD
    return _crack_mask_to_detections(mask, probability)


def _yolo_type_detections(defect_type: str, image: "Image.Image") -> list[DetectedDefect]:
    """`defect_type` 전용 YOLO 체크포인트로 탐지한다.

    체크포인트의 `model.names`(클래스 라벨 텍스트)는 신뢰하지 않는다 — 어떤 체크포인트를
    호출했는지 자체가 이미 유형을 확정하므로(yolo_client.py 모듈 docstring의 저장소 구조 변경
    배경 참고), 모든 탐지를 무조건 `defect_type`으로 라벨링한다.
    """
    model = get_yolo_model(defect_type)
    # 동시 추론 직렬화(코드 리뷰 P2, yolo_client.predict 참고) — model.predict를 직접 부르지 않는다.
    results = yolo_predict(model, source=image, conf=DEFAULT_CONFIDENCE_THRESHOLD, verbose=False)
    result = results[0]

    boxes = result.boxes
    if boxes is None or len(boxes) == 0:
        return []

    detections: list[DetectedDefect] = []
    xyxyn = boxes.xyxyn.tolist()
    confidences = boxes.conf.tolist()

    for i, (x1, y1, x2, y2) in enumerate(xyxyn):
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
                area_ratio=area_ratio,
            )
        )

    return detections


def run_defect_detection_chain(image_base64: str) -> list[DetectedDefect]:
    image = _decode_image(image_base64)

    detections: list[DetectedDefect] = []
    detections.extend(_crack_detections(image))
    detections.extend(_yolo_type_detections("SPALLING", image))
    detections.extend(_yolo_type_detections("REBAR_EXPOSURE", image))
    return detections
