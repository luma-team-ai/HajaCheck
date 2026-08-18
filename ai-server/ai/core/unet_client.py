"""균열(CRACK) 전용 U-Net(ResNet34) 세그멘테이션 모델 로더 — yolo_client.py와 동일한 "체인에서
직접 생성하지 않고 이 함수를 거친다" 패턴(AI_개발_컨벤션.md §0 공통 기반 원칙)이되, YOLO(인스턴스
탐지)가 아니라 픽셀 단위 이진 마스크만 내는 세그멘테이션 전용 구조라 별도 모듈로 분리했다.

## 배경 (dev-05-04, docs/_local/황승현_할일.md, 2026-07-27 6차 rebase)

`50seok/hajacheck-defect-detection` HF Hub 저장소가 애초 전제("모델 1개가 3클래스 전부 처리")에서
"하자 유형별 전용 체크포인트" 구조로 바뀌었다(저장소 README). 균열은 원래 YOLOv8-seg로 학습했으나
recall이 0.28에 그쳐(가늘고 긴 선형 하자에 인스턴스 탐지 구조가 부적합) **U-Net으로 교체 후 recall이
약 2배(0.28→0.55+)로 향상**됐다 — README가 `crack_unet_resnet34.pt` 사용을 명시적으로 권장한다
(구버전 `crack_yolov8s_seg.pt`는 참고용으로만 남아있음, yolo_client.py는 더 이상 이 파일을 쓰지 않음).

- **체크포인트가 순수 `state_dict`다** — ultralytics처럼 `torch.load(path)`로 바로 쓸 수 있는
  래핑된 모델이 아니라, `segmentation_models_pytorch.Unet(encoder_name="resnet34",
  encoder_weights=None, in_channels=3, classes=1)`을 먼저 만들고 `load_state_dict()`로 가중치만
  얹어야 한다(저장소 README 사용법 그대로). 실제로 `strict=True`로 전체 키 일치를 확인했다
  (segmentation-models-pytorch==0.5.0 기준, requirements.txt 주석 참고 — 버전이 바뀌면 내부 레이어
  이름이 달라져 로드가 깨질 수 있으니 업그레이드 시 재검증 필수).
- **박스·클래스·신뢰도가 없다** — 반환값은 픽셀별 확률(0~1) 맵 하나뿐이다. 인스턴스화(연결된
  균열 영역별 바운딩박스 역산)는 `ai/chains/defect_detection_chain.py`가 연결요소 분석
  (`cv2.connectedComponentsWithStats`)으로 후처리한다.

## 전처리 = 레터박스(긴 변 640 + 중앙 0패딩) — squash 아님 (2026-07-27, #973 후속 실측 수정)

HF Hub 저장소 README의 빠른 시작 예제는 `cv2.resize(img, (640, 640))`(정사각 squash)를 쓰지만,
이건 **README 저자가 편의상 든 예시일 뿐 실제 학습 코드(`train_crack_unet.py` CrackDataset,
레포 밖 `unet_handover` 인수인계본)와 다르다** — 학습은 train/val 공통으로
`A.LongestMaxSize(640) + A.PadIfNeeded(640,640,BORDER_CONSTANT)`, 즉 **레터박스**를 썼다.
PR #973 초기 구현은 README 예제를 따라 squash로 넣었는데, AI Hub 아파트 균열 470장 실측
(2026-07-27, 오영석) 결과 **탐지 성능(IoU/Recall) 자체는 squash와 거의 차이가 없었지만**
(U-Net이 완전 합성곱 구조라 종횡비 왜곡을 어느 정도 흡수하는 것으로 보임), **균열 없는 벽의
오탐 발생률·오탐 면적·등급 판별력(AUC)에서는 학습과 같은 레터박스가 명확히 낫다**는 게 확인돼
정정했다 — 즉 "더 잘 찾는다"가 아니라 "없는 균열을 덜 만들어낸다"는 효과다.
"""
from __future__ import annotations

import logging
import os
from functools import lru_cache
from typing import NamedTuple, TYPE_CHECKING

from ai.core.yolo_client import YOLO_REPO_ID

if TYPE_CHECKING:  # 타입 체커 전용 — 런타임 import 아님(torch 로드 회피)
    import numpy as np
    from PIL import Image
    from segmentation_models_pytorch import Unet

logger = logging.getLogger(__name__)

# #998, 2026-08-03 파인튜닝 모델로 교체, v1은 롤백용으로 HF 저장소에 유지.
CRACK_CHECKPOINT_FILENAME = "crack_unet_resnet34_v2.pt"

# 저장소 README 학습 설정 그대로(imgsz 640) — 모델이 검증받은 입력 해상도.
CRACK_INPUT_SIZE = 640
CRACK_MASK_THRESHOLD = 0.5

# ImageNet 정규화(원 학습이 encoder_weights="imagenet" 사전학습 기준으로 진행됨) — README 레시피 그대로.
_IMAGENET_MEAN = (0.485, 0.456, 0.406)
_IMAGENET_STD = (0.229, 0.224, 0.225)


class CrackPrediction(NamedTuple):
    """`predict_crack_probability`의 반환값 — 레터박스 캔버스 전체(패딩 포함) 확률 맵 + 그 안에서
    실제 이미지 콘텐츠가 차지하는 영역(패딩 오프셋/크기). 호출부(defect_detection_chain)가 패딩
    영역을 하드코딩된 상수로 추측하지 않고 이 값을 그대로 써서 잘라내게 하기 위해 함께 반환한다
    (2026-07-27 리뷰 — "패딩 오프셋/콘텐츠 크기를 호출부가 추측하지 않게 할 것").
    """

    probability: "np.ndarray"  # (CRACK_INPUT_SIZE, CRACK_INPUT_SIZE) — 패딩 영역 포함 전체 캔버스
    content_top: int
    content_left: int
    content_height: int
    content_width: int


def _letterbox_layout(width: int, height: int) -> tuple[int, int, int, int]:
    """원본 (width, height) -> CRACK_INPUT_SIZE 정사각 캔버스에 배치할 콘텐츠 영역
    (top, left, content_height, content_width). 긴 변을 CRACK_INPUT_SIZE에 맞춰 종횡비를
    유지한 채 축소하고, 남는 여백을 상하/좌우 중앙에 동일하게 나눈다 — 학습 코드
    (`train_crack_unet.py` CrackDataset)의 `A.LongestMaxSize(640) + A.PadIfNeeded`와 동일 레이아웃
    (배치 방식은 albumentations `PadIfNeeded` 기본값인 중앙 정렬을 따른다).
    """
    scale = CRACK_INPUT_SIZE / max(width, height)
    content_width = round(width * scale)
    content_height = round(height * scale)
    top = (CRACK_INPUT_SIZE - content_height) // 2
    left = (CRACK_INPUT_SIZE - content_width) // 2
    return top, left, content_height, content_width


@lru_cache
def get_crack_model() -> "Unet":
    """균열 세그멘테이션 모델. lru_cache로 프로세스당 1회만 다운로드+로드(yolo_client.get_yolo_model과
    동일 이유 — easyocr get_ocr_engine() 패턴).

    ## revision 고정 (#1645 — 재배포 전후 분석 결과 비재현)
    `UNET_REVISION`(HF 커밋 해시 또는 태그) 미설정 시 `hf_hub_download`가 저장소 `main` HEAD를
    받는다 — yolo_client.get_yolo_model()과 동일한 재배포 비재현 문제(docstring 참고)라 같은
    방식으로 고정한다. 설정 시 그 커밋에 고정, 미설정 시 현행(main HEAD) 폴백.
    """
    import segmentation_models_pytorch as smp
    import torch
    from huggingface_hub import hf_hub_download

    token = os.getenv("HF_API_TOKEN") or None
    revision = os.getenv("UNET_REVISION") or None
    if revision:
        logger.info("U-Net 균열 모델 로드 — revision=%s 고정", revision)
    else:
        logger.warning(
            "UNET_REVISION 미설정 — revision unpinned (main HEAD)로 U-Net 균열 모델 로드. "
            "재배포 시점마다 가중치가 달라질 수 있음(#1645)"
        )
    # cache_dir 미지정 시 huggingface_hub가 HF_HOME(도커 named volume /app/hf_cache, #439) 하위
    # 기본 경로를 그대로 쓴다 — yolo_client와 동일 볼륨 재사용으로 컨테이너 재기동 시 재다운로드 회피.
    weights_path = hf_hub_download(
        repo_id=YOLO_REPO_ID, filename=CRACK_CHECKPOINT_FILENAME, token=token, revision=revision
    )
    model = smp.Unet(encoder_name="resnet34", encoder_weights=None, in_channels=3, classes=1)
    # weights_only=True — 이 파일은 순수 state_dict(텐서만)라 안전하게 강제할 수 있다(임의 객체
    # 언피클링을 막는 안전한 기본값, HF Hub 저장소가 private이라도 방어적으로 유지).
    state_dict = torch.load(weights_path, map_location="cpu", weights_only=True)
    model.load_state_dict(state_dict)
    model.eval()
    return model


def predict_crack_probability(model: "Unet", image: "Image.Image") -> CrackPrediction:
    """RGB 이미지 -> 레터박스 캔버스 전체 확률 맵 + 콘텐츠 영역(패딩 제외) 정보.

    threshold·연결요소 분석은 호출부(defect_detection_chain)의 책임이다 — 여기서는 모델 순전파까지만
    수행한다(확률값 자체가 연결요소별 confidence 근사치로도 쓰이므로 threshold 이전 값을 반환한다).

    레터박스 배치(`_letterbox_layout`)로 이미지를 축소해 정사각 캔버스 중앙에 놓고, 남는 여백은
    검정(0) 픽셀로 채운다(정규화 이전 픽셀값 기준 0 — albumentations `PadIfNeeded`
    `border_mode=BORDER_CONSTANT`와 동일). 반환하는 확률 맵은 패딩을 포함한 캔버스 전체이고,
    패딩 영역을 실제로 잘라내는 건 호출부가 함께 반환된 콘텐츠 좌표로 수행한다.
    """
    from PIL import Image
    import numpy as np
    import torch

    width, height = image.size
    top, left, content_height, content_width = _letterbox_layout(width, height)

    # BILINEAR — 학습 코드(albumentations LongestMaxSize)의 기본 보간과 동일 계열로 맞춘다.
    # PIL의 resize() 기본값(BICUBIC)을 그대로 두면 학습 시 보지 않은 보간 방식의 입력이 된다.
    resized_content = image.resize(
        (content_width, content_height), resample=Image.Resampling.BILINEAR
    )
    canvas = Image.new("RGB", (CRACK_INPUT_SIZE, CRACK_INPUT_SIZE), (0, 0, 0))
    canvas.paste(resized_content, (left, top))

    # mean/std를 float32로 명시 캐스팅 — 그대로 두면 float64 파이썬 튜플과의 연산으로 배열 전체가
    # float64로 승격돼(~9.8MB) 불필요한 메모리를 쓴다.
    mean = np.asarray(_IMAGENET_MEAN, dtype=np.float32)
    std = np.asarray(_IMAGENET_STD, dtype=np.float32)
    arr = np.asarray(canvas, dtype=np.float32) / 255.0
    arr = (arr - mean) / std
    tensor = torch.from_numpy(arr.transpose(2, 0, 1)).float().unsqueeze(0)

    with torch.no_grad():
        logits = model(tensor)
        probability = torch.sigmoid(logits)

    return CrackPrediction(
        probability=probability.squeeze(0).squeeze(0).numpy(),
        content_top=top,
        content_left=left,
        content_height=content_height,
        content_width=content_width,
    )
