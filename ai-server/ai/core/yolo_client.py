"""박리박락(SPALLING)·철근노출(REBAR_EXPOSURE) 전용 YOLOv8-seg 모델 로더 — ocr_client.py/llm_client.py와
동일한 "체인에서 직접 생성하지 않고 이 함수를 거친다" 패턴(AI_개발_컨벤션.md §0 공통 기반 원칙).

## 배경 (dev-05-04, docs/_local/황승현_할일.md)

design-03-04(YOLOv8-seg 1차 학습) 완료 전이라 `models/MODEL_CARD.md`는 아직 TBD 상태고,
정식 배포 형식은 ONNX 변환+INT8 양자화(§4)다. 이 모듈은 그 전 단계로 HuggingFace Hub에 올라간
학습 체크포인트(.pt, ultralytics 포맷)를 직접 추론에 쓰는 **임시 경로**다 — ONNX 변환 파이프라인이
준비되면 이 모듈만 onnxruntime 기반으로 교체하면 되도록 인터페이스(get_yolo_model() -> 추론 가능한
객체 한 개)를 분리해뒀다.

- **모델 저장소**: private HF repo(`YOLO_MODEL_REPO_ID`, 기본 `50seok/hajacheck-defect-detection`).
  HF_API_TOKEN(기존 LLM/임베딩용과 동일 토큰, .env 공유)으로 인증.

## 저장소 구조 변경 (2026-07-27, 6차 rebase 중 발견 — dev-05-04 실추론 재중단 원인)

원래는 "모델 1개가 균열/박리박락/철근노출 3클래스 전부 처리"를 전제로, 파일명을 하드코딩하지 않고
저장소의 `.pt` 파일을 자동 탐색해 `best.pt`가 있으면 우선 선택하는 방식이었다. 그런데 저장소가
**하자 유형별 전용 체크포인트 4개**(`crack_unet_resnet34.pt`, `crack_yolov8s_seg.pt`(구버전),
`rebar_exposure_yolov8n_seg.pt`, `spalling_yolov8n_seg.pt`) 구조로 바뀌면서, `best.pt`가 없어
자동 탐색이 목록 첫 파일(U-Net 체크포인트 — ultralytics 포맷이 아님)을 집어 `KeyError: 'model'`로
매번 죽는 문제가 있었다(점검 #67 재현, `hajacheck-fastapi-1` 로그로 확인). 균열은 별도 U-Net
전용 모듈(`ai/core/unet_client.py`)로 분리했고, 이 모듈은 **파일명을 유형별로 고정 매핑**한다 —
저장소 README가 이미 파일명을 명시적 계약으로 문서화하고 있어(사용법 섹션 그대로), 더 이상
"파일명을 몰라도 동작"할 필요가 없다.

- **클래스 이름을 신뢰하지 않는다** — `rebar_exposure_yolov8n_seg.pt`의 `model.names`는
  `{0: "good", 1: "fair", 2: "poor"}`(상태 등급이지 하자 유형이 아님)라 라벨 텍스트로 유형을
  되짚는 게 위험하다(저장소 README 확인, 2026-07-27). 대신 **어떤 체크포인트를 호출했는지 자체가
  유형을 결정**한다 — `get_yolo_model(defect_type)`이 반환한 모델의 모든 탐지는 호출부
  (`defect_detection_chain.py`)가 `defect_type`으로 그대로 라벨링한다.
"""
from __future__ import annotations

import os
import threading
from functools import lru_cache
from typing import TYPE_CHECKING

if TYPE_CHECKING:  # 타입 체커 전용 — 런타임 import 아님(torch/cv2 로드 회피)
    from ultralytics import YOLO

YOLO_REPO_ID = os.getenv("YOLO_MODEL_REPO_ID", "50seok/hajacheck-defect-detection")

# 유형별 전용 체크포인트 파일명(저장소 README 사용법 섹션과 동일) — CRACK은 U-Net 전용
# (ai/core/unet_client.py)이라 여기 포함하지 않는다.
CHECKPOINT_FILENAMES: dict[str, str] = {
    "SPALLING": "spalling_yolov8n_seg.pt",
    "REBAR_EXPOSURE": "rebar_exposure_yolov8n_seg.pt",
}

# 공유 YOLO 인스턴스에 대한 동시 predict 직렬화(코드 리뷰 P2) — get_yolo_model()의 락과는 별개다.
# SPALLING/REBAR_EXPOSURE 두 모델 호출 전체를 이 하나의 락으로 직렬화한다(둘 다 프로세스 전역
# 공유 인스턴스이므로 보수적으로 함께 묶어도 무해하다 — 애초에 동시 분석 자체가 최대 2건
# (analysisTaskExecutor core=max=2)이라 별도 락으로 세분화할 이득이 크지 않다).
_predict_lock = threading.Lock()


@lru_cache
def get_yolo_model(defect_type: str) -> "YOLO":
    """`defect_type`(SPALLING/REBAR_EXPOSURE) 전용 체크포인트를 로드한다. 모든 하자 탐지 호출의
    시작점 — `ultralytics.YOLO(...)`를 직접 생성하지 않고 이 함수를 거친다.

    lru_cache로 유형별 프로세스당 1회만 다운로드+로드(easyocr get_ocr_engine()과 동일 이유).

    ## ultralytics(→torch/cv2) 지연 import 이유 (#573/ocr_client.py 패턴 유지)
    `ultralytics`는 import 시 torch·cv2(opencv)를 로드한다. 헤드리스 환경(CI/PR머신)에서 libGL이
    없으면 `import cv2`가 실패할 수 있어, 앱 import 경로(main.app 로드)가 이를 요구하지 않도록
    실제 추론을 수행하는 이 함수 내부로 지연시킨다. (arm1 런타임은 Dockerfile에 libgl1 설치로 해소 —
    EasyOCR과 동일 인프라 재사용.)
    """
    from huggingface_hub import hf_hub_download
    from ultralytics import YOLO

    token = os.getenv("HF_API_TOKEN") or None
    filename = CHECKPOINT_FILENAMES[defect_type]
    # cache_dir 미지정 시 huggingface_hub가 HF_HOME(도커 named volume /app/hf_cache, #439)
    # 하위 기본 경로를 그대로 쓴다 — easyocr/embeddings와 동일 볼륨을 재사용해 컨테이너 재기동 시
    # 재다운로드를 피한다.
    weights_path = hf_hub_download(repo_id=YOLO_REPO_ID, filename=filename, token=token)
    return YOLO(weights_path)


def predict(model: "YOLO", **kwargs):
    """model.predict(...)를 락으로 직렬화한다(코드 리뷰 P2).

    get_yolo_model()이 반환하는 인스턴스는 프로세스 전역 공유(@lru_cache, 사실상 싱글턴)다.
    ultralytics YOLO.predict()는 호출마다 인스턴스 내부의 predictor(배치·결과 상태를 인스턴스
    필드에 보관)를 재사용하도록 설계돼 있어 동시 호출에 대한 스레드 세이프를 보장하지 않는다.

    `/ai/detect-defects`는 async가 아닌 동기 `def`라 FastAPI가 외부 threadpool에서 실행하고,
    Spring `analysisTaskExecutor`(core=max=2)가 서로 다른 점검 회차를 동시에 분석하면 최대 2개
    요청이 같은 시점에 이 함수를 호출할 수 있다 — 락 없이 두면 결과가 다른 요청과 뒤섞여 잘못된
    회차·미디어에 하자가 저장되거나 예외로 이어질 수 있다.

    모델 로딩(get_yolo_model)은 이 락과 무관하다 — @lru_cache는 캐시 자료구조 접근만 스레드
    세이프할 뿐 캐시 미스 시 최초 실행(다운로드+로드) 자체를 직렬화하진 않는다(코드 리뷰 P3,
    머신 검수 2차). 정상 운영에서는 기동 시 워밍업(main.py `_warmup_yolo_model`)이 첫 호출을
    미리 끝내둬 이 창을 덮으므로 별도 락은 두지 않는다 — 추론(predict)만 직렬화하면 된다.
    """
    with _predict_lock:
        return model.predict(**kwargs)
