"""공통 YOLOv8-seg 하자 탐지 모델 로더 — ocr_client.py/llm_client.py와 동일한 "체인에서 직접
생성하지 않고 이 함수를 거친다" 패턴(AI_개발_컨벤션.md §0 공통 기반 원칙).

## 배경 (dev-05-04, docs/_local/황승현_할일.md)

design-03-04(YOLOv8-seg 1차 학습) 완료 전이라 `models/MODEL_CARD.md`는 아직 TBD 상태고,
정식 배포 형식은 ONNX 변환+INT8 양자화(§4)다. 이 모듈은 그 전 단계로 HuggingFace Hub에 올라간
학습 체크포인트(.pt, ultralytics 포맷)를 직접 추론에 쓰는 **임시 경로**다 — ONNX 변환 파이프라인이
준비되면 이 모듈만 onnxruntime 기반으로 교체하면 되도록 인터페이스(get_yolo_model() -> 추론 가능한
객체 한 개)를 분리해뒀다.

- **모델 저장소**: private HF repo(`YOLO_MODEL_REPO_ID`, 기본 `50seok/hajacheck-defect-detection`).
  HF_API_TOKEN(기존 LLM/임베딩용과 동일 토큰, .env 공유)으로 인증.
- **체크포인트 파일명은 하드코딩하지 않는다** — repo 파일 목록을 조회해 `.pt` 확장자를 자동 탐색하고,
  `best.pt`가 있으면 우선 선택한다(ultralytics 학습 산출물의 관례적 이름). 파일명을 몰라도 동작한다.
- **클래스 이름도 하드코딩하지 않는다** — ultralytics 체크포인트는 학습 시 `data.yaml`의 클래스
  순서를 `model.names`(dict[int, str])로 자체 내장한다. 인덱스 순서를 추측할 필요 없이 로드된
  모델에서 그대로 읽는다(grading.py의 라벨 정규화가 다양한 표기를 흡수한다).
"""
from __future__ import annotations

import os
import threading
from functools import lru_cache
from typing import TYPE_CHECKING

if TYPE_CHECKING:  # 타입 체커 전용 — 런타임 import 아님(torch/cv2 로드 회피)
    from ultralytics import YOLO

YOLO_REPO_ID = os.getenv("YOLO_MODEL_REPO_ID", "50seok/hajacheck-defect-detection")

# 공유 YOLO 인스턴스에 대한 동시 predict 직렬화(코드 리뷰 P2) — get_yolo_model()의 락과는 별개다.
_predict_lock = threading.Lock()


def _resolve_checkpoint_filename(token: str | None) -> str:
    from huggingface_hub import list_repo_files

    files = list_repo_files(YOLO_REPO_ID, token=token)
    pt_files = [f for f in files if f.endswith(".pt")]
    if not pt_files:
        raise RuntimeError(
            f"HF repo {YOLO_REPO_ID}에서 .pt 체크포인트를 찾지 못했습니다(파일 목록: {files})"
        )
    for f in pt_files:
        if f.rsplit("/", 1)[-1] == "best.pt":
            return f
    return pt_files[0]


@lru_cache
def get_yolo_model() -> "YOLO":
    """모든 하자 탐지 호출의 시작점. `ultralytics.YOLO(...)`를 직접 생성하지 않고 이 함수를 거친다.

    lru_cache로 프로세스당 1회만 다운로드+로드(easyocr get_ocr_engine()과 동일 이유).

    ## ultralytics(→torch/cv2) 지연 import 이유 (#573/ocr_client.py 패턴 유지)
    `ultralytics`는 import 시 torch·cv2(opencv)를 로드한다. 헤드리스 환경(CI/PR머신)에서 libGL이
    없으면 `import cv2`가 실패할 수 있어, 앱 import 경로(main.app 로드)가 이를 요구하지 않도록
    실제 추론을 수행하는 이 함수 내부로 지연시킨다. (arm1 런타임은 Dockerfile에 libgl1 설치로 해소 —
    EasyOCR과 동일 인프라 재사용.)
    """
    from huggingface_hub import hf_hub_download
    from ultralytics import YOLO

    token = os.getenv("HF_API_TOKEN") or None
    filename = _resolve_checkpoint_filename(token)
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
