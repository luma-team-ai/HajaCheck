"""공통 OCR 엔진 — EasyOCR 초기화를 한 곳에서 관리 (AI_개발_컨벤션.md §0 공통 기반
원칙, llm_client.py/embeddings.py와 동일 패턴 — 체인에서 EasyOCR을 직접 생성하지 않는다).

## RapidOCR → EasyOCR 교체 (#605)

RapidOCR(PP-OCRv1/v4 한국어 인식 wiring, #552)는 실측 결과 사업자등록증의 한글 상호명·
대표자명을 여전히 심하게 오인식했다(예: 법인명이 유사 한자로 깨지거나 대표자명이 다른
글자로 대체됨). 반면 **EasyOCR(한국어 모델)은 동일 문서에서 한글을 확연히 더 정확히
읽는다**(실측: 법인명 "(주)글로벌다이터로드", 대표자 "이삼욕", 개업일 "2024년 06린 24일"
수준의 미세 오탈자만 남고 문자 자체는 정상 한글로 인식됨 — 나머지는 LLM 오탈자 교정
(`ai/prompts/business_license_ocr.md`)으로 보정한다).

torch는 `sentence-transformers` 임베딩 파이프라인용으로 이미 이미지에 포함돼 있어(#439
hf_cache 볼륨과 동일 계열), EasyOCR 추가로 인한 이미지 무게 부담은 크지 않다.

- 모델: EasyOCR 공식 한국어(`ko`) + 영어(`en`) 인식 모델. `easyocr.Reader(['ko', 'en'])`
  최초 호출 시 EasyOCR이 자체적으로 다운로드해 로드한다(RapidOCR처럼 개별 rec 모델 경로를
  수동 지정할 필요 없음 — EasyOCR이 언어팩 단위로 관리).
- **모델 캐시 영속화**: 컨테이너 재기동 시 재다운로드를 피하기 위해 EasyOCR 모델 저장 경로를
  기존 `HF_HOME`(도커 named volume `/app/hf_cache`, `#439`) 하위 `easyocr/` 서브디렉터리로
  지정한다(`EASYOCR_MODEL_DIR`). 별도 named volume을 새로 만들지 않고 기존 볼륨에 얹어
  운영 배포 변경을 최소화한다.
- GPU 미사용(`gpu=False`) — arm1 런타임은 GPU가 없고, CPU 추론으로 충분한 처리량을 낸다.
"""
from __future__ import annotations

import os
from functools import lru_cache
from typing import TYPE_CHECKING

if TYPE_CHECKING:  # 타입 체커 전용 — 런타임 import 아님(torch/cv2 로드 회피)
    from easyocr import Reader

# HF_HOME(기존 hf_cache 볼륨, #439) 하위에 EasyOCR 전용 서브디렉터리를 둔다 — 컨테이너
# 재기동 시 재다운로드 방지, 볼륨 신규 생성 없이 기존 마운트를 재사용.
EASYOCR_MODEL_DIR = os.path.join(os.getenv("HF_HOME", "/app/hf_cache"), "easyocr")


@lru_cache
def get_ocr_engine() -> "Reader":
    """모든 OCR 체인의 시작점. `easyocr.Reader()`를 직접 생성하지 않고 이 함수를 거친다.

    lru_cache로 프로세스당 1회만 모델을 로드(다운로드+추론 세션 초기화 비용 상각).

    ## easyocr(→torch/cv2) 지연 import 이유 (#573 패턴 유지)
    `easyocr`는 import 시 `torch`·`cv2`(opencv)를 로드한다. 헤드리스 환경(CI/PR머신/도커
    최소 이미지)에서 `libGL`이 없으면 `import cv2`가 실패할 수 있다. 이 import를 모듈
    최상단에 두면 `main.app` import만으로(라우터→체인→이 모듈) 전체 테스트 수집이
    폭발한다(#552/#555 fallout과 동일 문제). 실제 OCR을 수행하는 이 함수 내부로
    지연시켜 앱 import 경로가 torch/cv2를 요구하지 않게 한다. (arm1 런타임은 Dockerfile에
    libgl1 설치로 해소.)
    """
    import easyocr  # noqa: PLC0415 — torch/cv2 로드 지연(헤드리스 테스트 수집 폭발 방지)

    os.makedirs(EASYOCR_MODEL_DIR, exist_ok=True)
    return easyocr.Reader(
        ["ko", "en"],
        gpu=False,
        model_storage_directory=EASYOCR_MODEL_DIR,
        download_enabled=True,
    )
