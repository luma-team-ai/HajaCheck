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
YOLO 체크포인트)를 통해 각각 독립적으로 탐지하고, `run_defect_detection_chain`이 유형별로 격리된
실패를 흡수하며(PR #973 P2-1 리뷰) 결과를 이미지 1장당 합친다.
"""
from __future__ import annotations

import base64
import binascii
import io
import logging
from typing import TYPE_CHECKING

from pydantic import BaseModel

from ai.core.grading import compute_crack_grade, compute_grade
from ai.core.unet_client import (
    CRACK_INPUT_SIZE,
    CRACK_MASK_THRESHOLD,
    get_crack_model,
    predict_crack_probability,
)
from ai.core.yolo_client import get_yolo_model
from ai.core.yolo_client import predict as yolo_predict

if TYPE_CHECKING:
    import numpy as np
    from PIL import Image

logger = logging.getLogger(__name__)

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

# U-Net 마스크의 연결요소 중 콘텐츠 영역(레터박스 패딩 제외) 픽셀 대비 이 비율 미만은 노이즈
# 스펙클로 간주해 버린다(PR #973 P2-2 리뷰) — 절대 픽셀 수(예: 20px)로 고정하면 입력 해상도가
# 바뀔 때 조용히 의미가 달라지므로 비율로 둔다. 균열 등급 최저 밴드
# (grading._CRACK_AREA_RATIO_SEVERITY_BANDS 첫 항목 0.275%, 2026-07-27 재보정)의 약 1/14 —
# 그보다 훨씬 작으면 등급 판정에 사실상 기여하지 못하는 잡음으로 본다.
MIN_CRACK_COMPONENT_AREA_RATIO = 0.0002

# 노이즈 많은 벽면 사진 한 장이 수십~수백 개의 별도 탐지 행을 만드는 것을 막는 상한(P2-2) — YOLO
# 경로는 NMS+max_det로 자연스러운 상한이 있었지만 U-Net 연결요소 경로엔 없었다. 면적 큰 순으로만
# 이만큼 남긴다.
MAX_CRACK_COMPONENTS = 30

# round(confidence, 4)가 0.99995 이상이면 1.0이 되는데, 백엔드 DefectRevisionService가
# confidence == 1.0을 "수동 생성" sentinel로 취급한다(#644 origin 컬럼 도입 전까지의 임시 구분법) —
# AI 탐지분이 수동 생성으로 오분류되지 않도록 상한을 살짝 낮춰 클램프한다(P3).
CONFIDENCE_CLAMP_MAX = 0.9999


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
    # 물리 측정값(원본 이미지 픽셀 단위, 보고서 표기용 — 등급 입력 아님). mm 환산은 사진 속
    # 기준물(촬영 규약 docs/design/crack-measurement-protocol.md)로 스케일을 얻은 뒤에만 가능하다.
    # Spring DetectedDefectItem은 @JsonIgnoreProperties(ignoreUnknown)라 additive-only로 안전
    # (backend/src/main/java/com/hajacheck/core/ai/dto/DetectedDefectItem.java:11 확인, #1447 P2).
    area_px: float | None = None  # 마스크 픽셀 수(원본 해상도 환산)
    length_px: float | None = None  # 균열만 — 면적/폭 근사
    width_px: float | None = None  # 균열만 — 2×면적/둘레 근사(skeletonize 대비 r=0.9989)
    width_mm: float | None = None  # 균열만 — 카드 기준물로 환산한 폭(원본 고해상도 재측정, #1487)


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


def _crack_dark_ratio(image: "Image.Image", mask: "np.ndarray") -> float:
    """어두움총량(dark_ratio) — 마스크 주변 밴드의 BLACKHAT 합 / (255 × 콘텐츠 픽셀 수).

    등급 v3(grading.py 모듈 docstring)의 두 번째 지표. U-Net 마스크 폭은 선 두께 하한(3~4px)에
    갇혀 실제 굵기를 못 재지만, 어두움총량은 서브픽셀 폭에도 비례하는 광학 신호라 "가늘지만 긴"
    실금과 진짜 굵은 균열을 가른다.

    ⚠️ 아래 세 조건은 dark 구간표(_CRACK_DARK_RATIO_SEVERITY_BANDS)가 측정된 캘리브레이션
    (cv_darksum.py, AI Hub 470장)과 동일하게 유지해야 한다 — 하나라도 바꾸면 구간표 재측정:
    ① 그레이스케일은 **원본 해상도**에서 변환 후 콘텐츠 크기로 INTER_AREA 축소(레터박스 캔버스의
    BILINEAR 결과를 재활용하지 않는다 — 보간이 달라 blackhat 값이 달라짐), ② BLACKHAT 커널
    ellipse 15×15, ③ 합산 범위는 마스크를 3×3 팽창시킨 밴드(마스크가 균열 중심선을 살짝 벗어나도
    어두운 픽셀을 놓치지 않게).
    """
    import cv2
    import numpy as np

    if not mask.any():
        return 0.0
    height, width = mask.shape
    gray = cv2.cvtColor(np.asarray(image), cv2.COLOR_RGB2GRAY)
    gray = cv2.resize(gray, (width, height), interpolation=cv2.INTER_AREA)
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (15, 15))
    blackhat = cv2.morphologyEx(gray, cv2.MORPH_BLACKHAT, kernel).astype(np.float32)
    band = cv2.dilate(mask.astype(np.uint8), np.ones((3, 3), np.uint8)) > 0
    return float(blackhat[band].sum()) / (255.0 * mask.size)


def _crack_component_measurements(closed_component_mask: "np.ndarray", area: int) -> tuple[float, float]:
    """연결요소 1개의 (length_px, width_px) — 콘텐츠 좌표계 기준(호출부가 원본 px로 스케일).

    폭은 `2×면적/둘레` 근사 — skeletonize(distance transform) 실측 대비 r=0.9989로 검증됐고
    (2026-07-28 조사), scikit-image 신규 의존성 없이 OpenCV만으로 계산된다. 면적은
    `cv2.contourArea`가 아니라 마스크 픽셀 수를 쓴다(contourArea는 -18.8% 과소평가 실측).
    길이=면적/폭. 등급 입력으로는 쓰지 않는다(폭 단독 지표는 촬영건 CV에서 과적합 확정).

    ⚠️ 둘레는 반드시 `closed_component_mask`(MORPH_CLOSE로 병합된, 원래 마스크와 교집합하기
    **이전**의 연결영역)로 구해야 한다(#1447 P2). threshold 잡음으로 하나의 균열이 원래 마스크
    상 여러 조각으로 끊겨 있어도 close가 채운 좁은 틈은 실제로는 균열의 일부다 — 원래 마스크와
    교집합한 파편화 마스크로 둘레를 구하면 `findContours`가 조각마다 별도 외곽선을 찾아 둘레를
    합산해버려(실측 재현: 8조각·1px 간극에서 폭 -7.5%, 길이 -5% 왜곡), 진짜 폭보다 작게·길이는
    크게 계산된다. 반면 면적(`area` 인자)은 close가 채운 가짜 픽셀을 빼야 하므로 여전히 원래
    마스크 기준 픽셀 수를 그대로 받는다 — 분자(면적)는 참값, 분모(둘레)만 "끊김 없는 하나의
    균열"이라는 전제에 맞는 닫힌 형태를 쓰는 조합이다.
    """
    import cv2

    contours, _ = cv2.findContours(
        closed_component_mask.astype("uint8"), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE
    )
    perimeter = sum(cv2.arcLength(c, closed=True) for c in contours)
    if perimeter <= 0:  # 1~2px 퇴화 케이스(최소 크기 필터보다 작아 실제론 도달 어려움)
        side = float(area) ** 0.5
        return side, side
    width = 2.0 * float(area) / perimeter
    return float(area) / width, width


def _crack_mask_to_detections(
    mask: "np.ndarray", probability: "np.ndarray", dark_ratio: float, scale: float = 1.0
) -> list[DetectedDefect]:
    """균열 확률 맵 -> 연결요소별 DetectedDefect 목록.

    `mask`/`probability`는 호출부(`_crack_detections`)가 **레터박스 패딩을 이미 잘라낸 콘텐츠
    영역만** 넘긴다는 전제다 — 이 함수 자체는 패딩의 존재를 모르고, 받은 배열 전체를 "이미지"로
    본다(area_ratio 분모·bbox 정규화 전부 이 배열의 shape 기준). 그래서 이 경계 한 곳에서만
    패딩을 처리하면 되고, 아래 로직은 패딩 유무와 무관하게 동일하다.

    ## 등급은 이미지 전체 마스크 면적 기준으로 1회만 산정한다 (PR #973 P1 리뷰)

    얇고 긴 선형 하자(균열)는 threshold 이후 마스크가 여러 조각으로 끊기는 게 예외가 아니라 기본
    동작에 가깝다(U-Net으로 교체한 이유 자체가 이 파편화 경향 — 저장소 README 참고). 컴포넌트별로
    개별 area_ratio를 매기면 "총 면적은 같은데 몇 조각으로 끊겼는지"에 따라 등급이 크게 널뛴다
    (실측 재현: 총면적 0.876%가 1덩어리면 E, 4조각(각 0.156%)이면 B). 그래서 등급은 이미지 전체
    마스크 면적(total_mask_ratio)과 어두움총량(dark_ratio)의 min 합의(grading.compute_crack_grade,
    v3)로 1회 산정해 모든 컴포넌트에 동일하게 적용하고, 컴포넌트별 area_ratio는 표시(바운딩박스
    크기 참고용)로만 남긴다.

    연결요소 분석 전에 `cv2.morphologyEx(MORPH_CLOSE)`로 가까운 조각을 먼저 병합한다 — 등급에는
    이미 영향이 없지만, threshold 잡음으로 사실상 하나인 균열이 여러 별도 탐지 행으로 쪼개지는
    것(P2-2)을 줄인다. `MIN_CRACK_COMPONENT_AREA_RATIO`(최소 크기)·`MAX_CRACK_COMPONENTS`
    (개수 상한)도 같은 목적이다.

    confidence는 인스턴스 단위 점수가 없으므로 해당 연결요소 내부 픽셀 중 **원래(닫기 연산 이전)
    threshold를 통과한 픽셀들**의 평균 확률로 근사한다(P2-3) — 마스크 자체가 이미
    CRACK_MASK_THRESHOLD(0.5) 초과 픽셀만으로 구성되므로 이 평균은 항상 0.5 이상이 보장된다
    (YOLO 경로처럼 별도 신뢰도 컷이 필요 없는 이유). 닫기 연산이 채운 틈새 픽셀은 원래 마스크
    기준으로 걸러 이 하한이 깨지지 않게 한다.
    """
    import cv2

    total_pixels = mask.size
    height, width = mask.shape
    total_mask_ratio = float(mask.sum()) / float(total_pixels)
    if total_mask_ratio == 0.0:
        return []
    image_grade = compute_crack_grade(total_mask_ratio, dark_ratio)

    mask_u8 = mask.astype("uint8")
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
    closed = cv2.morphologyEx(mask_u8, cv2.MORPH_CLOSE, kernel)
    num_labels, labels, stats, _ = cv2.connectedComponentsWithStats(closed, connectivity=8)

    min_component_pixels = MIN_CRACK_COMPONENT_AREA_RATIO * total_pixels
    components = []
    for label in range(1, num_labels):  # 0번 라벨=배경
        x, y, w, h, _closed_area = stats[label]
        # area_ratio는 close 이전 원본 마스크 기준으로 센다 — stats의 면적은 close가 채운 틈새
        # 픽셀까지 포함해 실측상 최대 1.49배 과대 계상되고(재검수 N-3), 이 값이 Defect.areaRatio로
        # DB에 영구 저장돼 YOLO 경로(실제 마스크 기준)와 의미가 어긋난다. confidence 계산에 이미
        # 만드는 "원본 마스크 교집합"을 그대로 재사용해 한 번만 계산한다.
        component_mask = (labels == label) & mask
        original_pixels = probability[component_mask]
        original_area = int(original_pixels.size)
        if original_area < min_component_pixels:
            continue
        # 둘레는 닫힌 연결영역(labels == label) 기준 — 이유는 _crack_component_measurements
        # 독스트링 참고(#1447 P2, 파편화된 component_mask를 쓰면 둘레가 부풀어 폭이 과소평가된다).
        length_px, width_px = _crack_component_measurements(labels == label, original_area)
        components.append((original_area, label, x, y, w, h, original_pixels, length_px, width_px))

    # 면적 큰 순으로 상한만큼만 남긴다(P2-2).
    components.sort(key=lambda c: c[0], reverse=True)
    components = components[:MAX_CRACK_COMPONENTS]

    detections: list[DetectedDefect] = []
    for area, _label, x, y, w, h, original_pixels, length_px, width_px in components:
        area_ratio = float(area) / float(total_pixels)
        confidence = float(original_pixels.mean()) if original_pixels.size > 0 else CRACK_MASK_THRESHOLD

        detections.append(
            DetectedDefect(
                type="CRACK",
                # bbox는 close로 병합된 영역 그대로 둔다(표시용 — 잡음으로 끊긴 조각을 시각적으로
                # 하나의 균열처럼 보여주는 게 목적이라 area_ratio와 달리 부풀림 문제가 없다).
                bbox_x=x / width,
                bbox_y=y / height,
                bbox_w=w / width,
                bbox_h=h / height,
                confidence=min(round(confidence, 4), CONFIDENCE_CLAMP_MAX),
                grade=image_grade,
                area_ratio=area_ratio,
                # scale = 원본px/콘텐츠px — 측정값은 mm 환산 기준이 되는 원본 해상도로 통일한다.
                area_px=round(float(area) * scale * scale, 1),
                length_px=round(length_px * scale, 1),
                width_px=round(width_px * scale, 1),
            )
        )
    return detections


def _crack_detections(image: "Image.Image") -> list[DetectedDefect]:
    """레터박스 캔버스에서 패딩을 잘라내 콘텐츠 영역만 `_crack_mask_to_detections`에 넘긴다.

    unet_client가 학습과 동일한 레터박스(긴 변 640 + 중앙 0패딩)로 추론하므로(2026-07-27, #973
    후속 실측 수정), 640x640 캔버스 중 실제 이미지 픽셀이 아닌 여백(1080x1440 입력 기준 캔버스의
    약 25%)이 섞여 있다. 이 경계에서 한 번만 잘라내면 area_ratio 분모·bbox 정규화 모두 자동으로
    "원본 이미지 기준"이 된다 — 패딩 오프셋을 아래 함수 곳곳에 하드코딩해 흩뿌리지 않는다(그렇게
    두면 area_ratio가 콘텐츠 비율만큼 축소돼 #953이 고쳤던 "전부 A" 버그가 재발한다).

    또한 카드(기준물) 자동검출을 시도해 스케일(mm/px)을 산출하고, 성공 시 각 균열의 width_mm을
    계산한다 (#1487). 카드 미검출 또는 폭 <0.7mm인 경우는 width_mm을 None으로 유지한다(신뢰
    구간 밖).
    """
    import cv2
    import numpy as np

    from ai.core.card_client import CARD_LONG_MM, detect_card
    from ai.core.crack_mm_measurement import measure_crack_width_mm

    model = get_crack_model()
    prediction = predict_crack_probability(model, image)
    top, left = prediction.content_top, prediction.content_left
    height, width = prediction.content_height, prediction.content_width
    content_probability = prediction.probability[top : top + height, left : left + width]
    content_mask = content_probability > CRACK_MASK_THRESHOLD
    dark_ratio = _crack_dark_ratio(image, content_mask)
    # 레터박스는 종횡비를 보존하므로 가로/세로 스케일이 같다 — 측정값(px)을 원본 해상도로 환산.
    scale = image.width / width if width > 0 else 1.0

    detections = _crack_mask_to_detections(content_mask, content_probability, dark_ratio, scale=scale)

    # 균열이 하나도 없으면 카드 검출 자체를 건너뛴다 — width_mm은 CRACK detection에만 붙는
    # 부가 정보인데, 고비용 YOLO-World 추론(imgsz=2048, 실패 시 2x2 타일 재검출로 요청당 최대
    # 5회)을 균열 없는 사진(대다수)에서 매번 돌 이유가 없다(#1547 P2).
    if not detections:
        return detections

    # 카드 검출 및 mm 환산 시도 — width_mm은 부가 정보(설계상 미검출 시 None)라, 이 호출이
    # 던지는 예외가 균열 탐지 자체를 무너뜨리면 안 된다. _crack_detections는 상위 detect_defects의
    # 유형별 try/except(카드 예외 없이도 존재)로 감싸여 있어, 여기서 예외가 새면 이미 구한 균열
    # detections까지 통째로 failed_types["CRACK"]로 사라진다(#1547 정재봉 P1 리뷰).
    try:
        card_result = detect_card(image)
    except Exception:
        logger.exception("카드 검출 실패 — width_mm 없이 균열 탐지는 계속 진행한다 (#1547)")
        card_result = None
    if card_result:
        # mm/px(카드 실제 크기 ÷ 카드 픽셀 길이) — px/mm과 방향 반대이니 헷갈리지 말 것(#1547 P1).
        card_scale_mm_per_px = CARD_LONG_MM / card_result.long_px
        # 원본 해상도 BGR 변환 — measure_crack_width_mm의 BLACKHAT 재측정 입력(축소본이 아니라
        # 원본을 그대로 쓰는 것이 B안의 핵심).
        image_bgr = cv2.cvtColor(np.array(image), cv2.COLOR_RGB2BGR)
        content_h, content_w = content_probability.shape
        for detection in detections:
            if detection.type == "CRACK" and detection.width_px is not None:
                # detection 자신의 bbox 영역만 남긴 마스크로 측정한다 — 이미지 전체
                # content_probability를 그대로 넘기면 measure가 내부에서 "가장 큰 연결요소"
                # 하나만 재므로, 분리된 균열이 여러 개일 때 모든 detection에 같은(가장 큰
                # 균열의) 폭이 들어가는 정합성 버그가 된다(#1547 P2). bbox는 콘텐츠 좌표
                # 정규화값이라 원복 시 ±2px 여유를 둔다(반올림 오차 방어).
                x0 = max(0, int(detection.bbox_x * content_w) - 2)
                y0 = max(0, int(detection.bbox_y * content_h) - 2)
                x1 = min(content_w, int((detection.bbox_x + detection.bbox_w) * content_w) + 2)
                y1 = min(content_h, int((detection.bbox_y + detection.bbox_h) * content_h) + 2)
                scoped_probability = np.zeros_like(content_probability)
                scoped_probability[y0:y1, x0:x1] = content_probability[y0:y1, x0:x1]
                # content_probability는 패딩 제거본, crack_input_size는 고정 640(레터박스 캔버스
                # 크기 — 비정사각 사진에서 콘텐츠 폭과 다르다)이어야 좌표가 맞는다(#1547 P1).
                width_mm = measure_crack_width_mm(
                    image_bgr,
                    scoped_probability,
                    card_scale_mm_per_px,
                    crack_input_size=CRACK_INPUT_SIZE,
                    crack_mask_threshold=CRACK_MASK_THRESHOLD,
                )
                # 0.7mm 이상만 기록 (미만은 신뢰도 부족)
                if width_mm and width_mm >= 0.7:
                    detection.width_mm = round(width_mm, 4)

    return detections


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

    total_pixels = image.width * image.height
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
                confidence=min(round(float(confidences[i]), 4), CONFIDENCE_CLAMP_MAX),
                grade=grade,
                area_ratio=area_ratio,
                # 면적비 × 원본 픽셀 수 — 박리박락·철근노출의 mm² 환산(기준물 확보 시) 기반값.
                area_px=round(area_ratio * total_pixels, 1),
            )
        )

    return detections


def run_defect_detection_chain(image_base64: str) -> list[DetectedDefect]:
    """세 모델(CRACK/SPALLING/REBAR_EXPOSURE)을 각각 호출해 결과를 합친다.

    ## 유형별 실패 격리 (PR #973 P2-1 리뷰)

    세 모델을 순차 호출하되 유형별로 예외를 격리한다 — U-Net(신규 의존성 + strict
    load_state_dict + private HF 파일이라 실패 확률이 상대적으로 높음) 하나가 실패해도
    SPALLING/REBAR_EXPOSURE까지 통째로 실패하면, 이 PR이 고치는 사고("모델 1개 문제로 분석
    전면 중단")와 같은 실패 모드가 유형 수만큼 늘어난 채로 남는다. 실패한 유형은
    `logger.exception`으로 반드시 로그에 남기고(조용한 부분 성공이 더 위험하다), 전 유형이 모두
    실패했을 때만 요청 자체를 실패 처리한다. 응답 스키마에 실패 유형을 구조화해 노출하는 것(예:
    `data.failed_types`)은 Spring 쪽 Envelope 계약 변경이 필요해 이 PR 범위를 벗어난다 — 후속
    이슈로 분리.
    """
    image = _decode_image(image_base64)

    detectors = (
        ("CRACK", lambda: _crack_detections(image)),
        ("SPALLING", lambda: _yolo_type_detections("SPALLING", image)),
        ("REBAR_EXPOSURE", lambda: _yolo_type_detections("REBAR_EXPOSURE", image)),
    )

    detections: list[DetectedDefect] = []
    failed_types: list[str] = []
    for defect_type, detect in detectors:
        try:
            detections.extend(detect())
        except Exception:
            logger.exception("%s 유형 탐지 실패 — 나머지 유형은 계속 진행한다", defect_type)
            failed_types.append(defect_type)

    if len(failed_types) == len(detectors):
        raise DefectDetectionError("모든 하자 유형 탐지에 실패했습니다")

    return detections
