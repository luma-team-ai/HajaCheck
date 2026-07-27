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
- **전처리(리사이즈·정규화)·threshold는 저장소 README의 학습 시 레시피를 그대로 따른다** — 다른
  값을 쓰면 모델이 검증받지 않은 입력 분포를 보게 된다.
- **박스·클래스·신뢰도가 없다** — 반환값은 픽셀별 확률(0~1) 맵 하나뿐이다. 인스턴스화(연결된
  균열 영역별 바운딩박스 역산)는 `ai/chains/defect_detection_chain.py`가 연결요소 분석
  (`cv2.connectedComponentsWithStats`)으로 후처리한다.
"""
from __future__ import annotations

import os
from functools import lru_cache
from typing import TYPE_CHECKING

from ai.core.yolo_client import YOLO_REPO_ID

if TYPE_CHECKING:  # 타입 체커 전용 — 런타임 import 아님(torch 로드 회피)
    import numpy as np
    from PIL import Image
    from segmentation_models_pytorch import Unet

CRACK_CHECKPOINT_FILENAME = "crack_unet_resnet34.pt"

# 저장소 README 학습 설정 그대로(imgsz 640) — 모델이 검증받은 입력 해상도. 원본 종횡비는 유지하지
# 않고 정사각형으로 리사이즈한다(README 레시피와 동일) — 정규화 좌표(0~1)로 변환하면 이 왜곡과
# 무관하게 원본 이미지에서의 올바른 위치를 가리키므로 downstream(defect_detection_chain)에는 영향 없다.
CRACK_INPUT_SIZE = 640
CRACK_MASK_THRESHOLD = 0.5

# ImageNet 정규화(원 학습이 encoder_weights="imagenet" 사전학습 기준으로 진행됨) — README 레시피 그대로.
_IMAGENET_MEAN = (0.485, 0.456, 0.406)
_IMAGENET_STD = (0.229, 0.224, 0.225)


@lru_cache
def get_crack_model() -> "Unet":
    """균열 세그멘테이션 모델. lru_cache로 프로세스당 1회만 다운로드+로드(yolo_client.get_yolo_model과
    동일 이유 — easyocr get_ocr_engine() 패턴)."""
    import segmentation_models_pytorch as smp
    import torch
    from huggingface_hub import hf_hub_download

    token = os.getenv("HF_API_TOKEN") or None
    # cache_dir 미지정 시 huggingface_hub가 HF_HOME(도커 named volume /app/hf_cache, #439) 하위
    # 기본 경로를 그대로 쓴다 — yolo_client와 동일 볼륨 재사용으로 컨테이너 재기동 시 재다운로드 회피.
    weights_path = hf_hub_download(
        repo_id=YOLO_REPO_ID, filename=CRACK_CHECKPOINT_FILENAME, token=token
    )
    model = smp.Unet(encoder_name="resnet34", encoder_weights=None, in_channels=3, classes=1)
    # weights_only=True — 이 파일은 순수 state_dict(텐서만)라 안전하게 강제할 수 있다(임의 객체
    # 언피클링을 막는 안전한 기본값, HF Hub 저장소가 private이라도 방어적으로 유지).
    state_dict = torch.load(weights_path, map_location="cpu", weights_only=True)
    model.load_state_dict(state_dict)
    model.eval()
    return model


def predict_crack_probability(model: "Unet", image: "Image.Image") -> "np.ndarray":
    """RGB 이미지 -> (CRACK_INPUT_SIZE, CRACK_INPUT_SIZE) 픽셀별 균열 확률(0~1) 맵.

    threshold·연결요소 분석은 호출부(defect_detection_chain)의 책임이다 — 여기서는 모델 순전파까지만
    수행한다(확률값 자체가 연결요소별 confidence 근사치로도 쓰이므로 threshold 이전 값을 반환한다).
    """
    from PIL import Image
    import numpy as np
    import torch

    # BILINEAR — 저장소 README 레시피의 cv2.resize 기본 보간(INTER_LINEAR)과 동일 계열로 맞춘다.
    # PIL의 resize() 기본값(BICUBIC)을 그대로 두면 학습 시 보지 않은 보간 방식의 입력이 된다(P3 리뷰).
    resized = image.resize((CRACK_INPUT_SIZE, CRACK_INPUT_SIZE), resample=Image.Resampling.BILINEAR)
    # mean/std를 float32로 명시 캐스팅 — 그대로 두면 float64 파이썬 튜플과의 연산으로 배열 전체가
    # float64로 승격돼(~9.8MB) 불필요한 메모리를 쓴다(P3 리뷰).
    mean = np.asarray(_IMAGENET_MEAN, dtype=np.float32)
    std = np.asarray(_IMAGENET_STD, dtype=np.float32)
    arr = np.asarray(resized, dtype=np.float32) / 255.0
    arr = (arr - mean) / std
    tensor = torch.from_numpy(arr.transpose(2, 0, 1)).float().unsqueeze(0)

    with torch.no_grad():
        logits = model(tensor)
        probability = torch.sigmoid(logits)

    return probability.squeeze(0).squeeze(0).numpy()
