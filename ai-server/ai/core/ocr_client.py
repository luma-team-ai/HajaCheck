"""공통 OCR 엔진 — RapidOCR(ONNXRuntime) 초기화를 한 곳에서 관리 (AI_개발_컨벤션.md §0 공통 기반
원칙, llm_client.py/embeddings.py와 동일 패턴 — 체인에서 RapidOCR을 직접 생성하지 않는다).

## 한국어 인식 wiring (HAJA-169/#552)

RapidOCR 기본 인식 모델(`ch_PP-OCRv4_rec`)은 중국어·영어 위주로 학습돼 있어 사업자등록증의
한글 상호명·대표자명을 인식하지 못한다(실측: 한글 라인이 통째로 유사 한자로 오인식되거나
신뢰도 미달로 드롭됨). 검출(Det)·분류(Cls) 모델은 언어 비의존적(문자 영역 vs 배경 판별)이라
기본 다국어 모델을 그대로 쓰고, **인식(Rec) 모델만 한국어 전용으로 교체**한다.

- 모델: PaddleOCR PP-OCRv1 한국어 인식 모델을 RapidOCR 관리자가 ONNX로 변환해 배포한 것
  (`huggingface.co/SWHL/RapidOCR` 저장소의 `PP-OCRv1/korean_mobile_v2.0_rec_infer.onnx`).
  문자 딕셔너리(3688자, PaddleOCR `korean_dict.txt`와 일치)가 ONNX 모델 메타데이터에 이미
  내장돼 있어(`custom_metadata_map["character"]`) 별도 `rec_keys_path` 지정이 불필요하다
  (RapidOCR 자체 변환 모델과 동일한 배포 관례).
- `rec_img_shape=[3, 32, 320]`: PP-OCRv1 계열은 32px 높이로 학습됨(PaddleOCR
  `configs/rec/multi_language/rec_korean_lite_train.yml` 확인) — RapidOCR 기본 config.yaml의
  48px(PP-OCRv4용) 그대로 쓰면 인식률이 떨어진다.
- 모델 파일(3.3MB)은 저장소에 커밋하지 않는다 — `embeddings.py`의 bge-m3와 동일하게
  `huggingface_hub.hf_hub_download()`로 런타임 최초 호출 시 1회 다운로드해 `HF_HOME`
  (도커 named volume `/app/hf_cache`, `#439`)에 캐시한다. 컨테이너 재시작 후에도 재다운로드하지
  않는다. 다운로드 실패(네트워크 장애 등)는 호출부(체인)에서 그대로 예외로 전파되며,
  라우터가 표준 폴백(`AIResponse.fail`)으로 흡수한다.
- 실측(로컬): 기본 ch 모델은 "등록번호:123-45-67890" 라인을 "号世豆：123-45-67890"으로 오인식.
  한국어 wiring 적용 후 동일 라인 신뢰도 0.97로 정확히 인식.
"""
from __future__ import annotations

from functools import lru_cache
from typing import TYPE_CHECKING

from huggingface_hub import hf_hub_download

if TYPE_CHECKING:  # 타입 체커 전용 — 런타임 import 아님(cv2 로드 회피)
    from rapidocr_onnxruntime import RapidOCR

KOREAN_REC_REPO_ID = "SWHL/RapidOCR"
KOREAN_REC_FILENAME = "PP-OCRv1/korean_mobile_v2.0_rec_infer.onnx"
KOREAN_REC_IMG_SHAPE = [3, 32, 320]


@lru_cache
def get_ocr_engine() -> "RapidOCR":
    """모든 OCR 체인의 시작점. `RapidOCR()`을 직접 생성하지 않고 이 함수를 거친다.

    lru_cache로 프로세스당 1회만 모델을 로드(다운로드+ONNX 세션 초기화 비용 상각).

    ## rapidocr(→cv2) 지연 import 이유 (#573)
    `rapidocr_onnxruntime`는 import 시 `cv2`(opencv)를 로드하는데, 헤드리스 환경
    (CI/PR머신/도커 최소 이미지)에 `libGL`이 없으면 `import cv2`가 실패한다. 이 import를
    모듈 최상단에 두면 `main.app` import만으로(라우터→체인→이 모듈) 전체 테스트 수집이
    폭발했다(#552/#555 fallout). 실제 OCR을 수행하는 이 함수 내부로 지연시켜 앱 import
    경로가 cv2를 요구하지 않게 한다. (arm1 런타임은 Dockerfile에 libgl1 설치로 해소.)
    """
    from rapidocr_onnxruntime import RapidOCR  # noqa: E402,PLC0415 — cv2 로드 지연

    rec_model_path = hf_hub_download(
        repo_id=KOREAN_REC_REPO_ID, filename=KOREAN_REC_FILENAME
    )
    return RapidOCR(rec_model_path=rec_model_path, rec_img_shape=KOREAN_REC_IMG_SHAPE)
