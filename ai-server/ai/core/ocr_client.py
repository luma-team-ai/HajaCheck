"""공통 OCR 엔진 — RapidOCR 초기화를 한 곳에서 관리 (AI_개발_컨벤션.md §0 공통 기반
원칙, llm_client.py/embeddings.py와 동일 패턴 — 체인에서 RapidOCR을 직접 생성하지 않는다).

## EasyOCR → RapidOCR(PP-OCRv5 한국어 인식모델) 교체 (#722)

EasyOCR의 사업자등록증 인식 품질이 낮았다(실측 4문서×5필드=20개 중 완전일치 3/20, 대표자명은
4문서 전부 오인식). **RapidOCR + PP-OCRv5 한국어 인식모델**로 교체하면 18/20으로 개선된다
(추론도 4장 5.8s → 1.0s로 빨라지고, 모델도 ~95MB → 13MB로 가벼워진다).

### 이건 #605의 되돌리기가 아니다
#552가 쓰던 RapidOCR과 **라이브러리 계열은 같지만 인식모델이 다른 물건**이다.

| | #552(#605에서 제거) | 이번(#722) |
|---|---|---|
| 패키지 | `rapidocr-onnxruntime` 1.4.4 | `rapidocr` 3.9.x |
| 인식모델 | `PP-OCRv1/korean_mobile_v2.0_rec`(2020) | `korean_PP-OCRv5_rec_mobile`(2025) |
| cls | 미지정 → 기본 켜짐 | 명시적 끔 |

옛 구성을 그대로 재현해 분해한 실측(등록증 4종×5필드=20개, 완전일치 기준):
- #552 옛 구성(PP-OCRv1 + cls 켬): 5/20
- ↑ cls만 끔: 8/20
- 이번 구성(PP-OCRv5 + cls 끔): 18/20

→ 모델 세대 교체가 +10필드, cls 설정이 +3필드를 만들었다. #605 당시 EasyOCR로 교체한 판단은
그 시점 기준으로 옳았다(당시엔 PP-OCRv5 한국어 모델 조합을 검증하지 않았다).

## 🔑 `Global.use_cls: False`는 실측으로 확정된 필수 설정

RapidOCR 기본값 `use_cls: true`(텍스트 방향 분류기)가 사업자등록증 같은 **가로쓰기 정형
문서에서 텍스트라인을 뒤집어** `()()`, `(움C)9YYY` 같은 쓰레기를 만든다(#552에도 있던
함정). 이 설정을 켠 채로 두면 완전일치가 12/20까지 떨어진다(위 20개 기준). 반드시 False로
유지할 것 — 리뷰에서 이 값이 지워지거나 True로 바뀌면 회귀다.

- 모델: RapidOCR ONNXRuntime 엔진 + PP-OCRv5 한국어(`korean`) 인식모델(mobile). 검출(Det)은
  기본값(PP-OCRv6 ch mobile, 언어 독립적)을 그대로 쓴다 — 실측(#722 ablation)에서 검출기를
  건드리지 않은 이 조합이 이미 18/20을 달성해 별도 조정이 불필요했다.
- **모델 캐시 영속화**: 컨테이너 재기동 시 재다운로드를 피하기 위해 RapidOCR 모델 저장 경로를
  기존 `HF_HOME`(도커 named volume `/app/hf_cache`, `#439`) 하위 `rapidocr/` 서브디렉터리로
  지정한다(`RAPIDOCR_MODEL_ROOT_DIR`, `Global.model_root_dir`). RapidOCR은 이 파라미터를
  지정하지 않으면 **패키지 설치 경로 안**(`site-packages/rapidocr/models/`)에 모델을 받는데,
  런타임 이미지의 venv는 `fastapi`(uid 999) 소유가 아니라서(빌드 스테이지에서 root로 생성 후
  `COPY --from=build`로 그대로 옮겨짐) 쓰기 권한도 없고, 컨테이너 재생성마다 재다운로드도
  된다 — 반드시 hf_cache 볼륨 하위 경로로 지정해야 한다.
- 컨테이너 유저는 `fastapi`(uid 999)다. `os.makedirs(..., exist_ok=True)`로 모델 디렉터리를
  미리 만들어 쓰기 권한 문제를 방지한다(#605에서 EasyOCR가 동일한 이유로 첫 요청이
  PermissionError로 통째로 실패한 전례가 있다 — RapidOCR도 동일 원칙 적용).
"""
from __future__ import annotations

import os
from functools import lru_cache
from typing import TYPE_CHECKING

if TYPE_CHECKING:  # 타입 체커 전용 — 런타임 import 아님(rapidocr가 cv2를 로드하므로 회피)
    from rapidocr import RapidOCR

# HF_HOME(기존 hf_cache 볼륨, #439) 하위에 RapidOCR 전용 서브디렉터리를 둔다 — 컨테이너
# 재기동 시 재다운로드 방지, 볼륨 신규 생성 없이 기존 마운트를 재사용한다.
RAPIDOCR_MODEL_ROOT_DIR = os.path.join(os.getenv("HF_HOME", "/app/hf_cache"), "rapidocr")


@lru_cache
def get_ocr_engine() -> "RapidOCR":
    """모든 OCR 체인의 시작점. `RapidOCR()`을 직접 생성하지 않고 이 함수를 거친다.

    lru_cache로 프로세스당 1회만 모델을 로드(다운로드+추론 세션 초기화 비용 상각).

    ## rapidocr(→cv2) 지연 import 이유 (#573/#605 패턴 유지)
    `rapidocr`는 import 시 `cv2`(opencv)·`onnxruntime`을 로드한다. 헤드리스 환경(CI/PR머신/
    도커 최소 이미지)에서 `libGL`이 없으면 `import cv2`가 실패할 수 있다. 이 import를 모듈
    최상단에 두면 `main.app` import만으로(라우터→체인→이 모듈) 전체 테스트 수집이
    폭발한다(#552/#555 fallout과 동일 문제). 실제 OCR을 수행하는 이 함수 내부로 지연시켜
    앱 import 경로가 cv2/onnxruntime을 요구하지 않게 한다. (arm1 런타임은 Dockerfile에
    libgl1 설치로 해소.)
    """
    from rapidocr import EngineType, LangRec, ModelType, OCRVersion, RapidOCR  # noqa: PLC0415 — cv2 로드 지연(헤드리스 테스트 수집 폭발 방지)

    os.makedirs(RAPIDOCR_MODEL_ROOT_DIR, exist_ok=True)
    return RapidOCR(
        params={
            "Global.model_root_dir": RAPIDOCR_MODEL_ROOT_DIR,
            "Rec.engine_type": EngineType.ONNXRUNTIME,
            "Rec.lang_type": LangRec.KOREAN,
            "Rec.ocr_version": OCRVersion.PPOCRV5,
            "Rec.model_type": ModelType.MOBILE,
            # 필수 — 위 모듈 docstring "Global.use_cls: False는 실측으로 확정된 필수 설정" 참조.
            # 가로쓰기 정형 문서(사업자등록증)에서 방향 분류기가 텍스트라인을 뒤집어 쓰레기
            # 값을 만든다(완전일치 18/20 → 12/20으로 하락 실측). 절대 True로 바꾸지 말 것.
            "Global.use_cls": False,
        },
    )
