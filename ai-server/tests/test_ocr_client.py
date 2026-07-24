"""ai/core/ocr_client.py 단위 테스트 (#722, 이전 #605) — `get_ocr_engine()`이
`RapidOCR`을 올바른 인자로 호출하는지 검증한다. 실제 모델 다운로드/추론(RapidOCR 인스턴스화
내부 동작)은 하지 않는다 — `rapidocr.RapidOCR` 클래스 자체를 모킹한다.

## 회귀 방지 핵심: `Global.use_cls: False` (#722)
RapidOCR 기본값 `use_cls: true`(텍스트 방향 분류기)가 사업자등록증 같은 가로쓰기 정형
문서에서 텍스트라인을 뒤집어 쓰레기 값을 만든다(실측: 완전일치 18/20 → 12/20으로 하락).
이 파일은 `RapidOCR(params={...})`에 `Global.use_cls`가 항상 `False`로 전달되는지, 그리고
모델 캐시 경로(`Global.model_root_dir`)가 hf_cache 볼륨 하위로 지정되는지를 고정한다.

`rapidocr`는 import 시 cv2/onnxruntime을 로드하는데, 실제 로드하면 종료 시 세그폴트를
유발하므로(easyocr 시절 #605와 동일 문제 패턴) 이 파일은 `sys.modules`에 가짜 rapidocr를
심어 실제 로드를 회피한다. 앱/체인 import 경로는 원래 rapidocr를 요구하지 않는다(지연 import
#573/#605, `test_business_license_ocr.py`가 모킹 검증).
"""
import sys
from unittest.mock import MagicMock, patch

import pytest  # noqa: F401 — fixture 데코레이터에서 사용

# ⚠️ rapidocr는 import 시 cv2/onnxruntime을 로드하는데, 이를 실제로 로드하면 같은 프로세스의
# chromadb 등과 함께 **pytest 종료 시 Segmentation fault**(native 라이브러리 정리 순서 충돌)를
# 유발할 수 있다(#605 회귀 패턴과 동일한 위험군). 이 테스트는 get_ocr_engine()이
# rapidocr.RapidOCR을 올바른 인자로 호출하는지만 검증하므로, 실제 rapidocr를 import하지 않고
# sys.modules에 가짜 모듈을 심는다(cv2/onnxruntime 미로드 → 세그폴트 회피).
# get_ocr_engine 내부의 지연 `from rapidocr import ...`도 이 가짜를 받는다.
_fake_rapidocr = MagicMock()
_fake_rapidocr.EngineType.ONNXRUNTIME = "onnxruntime"
_fake_rapidocr.LangRec.KOREAN = "korean"
_fake_rapidocr.OCRVersion.PPOCRV5 = "PP-OCRv5"
_fake_rapidocr.ModelType.MOBILE = "mobile"
sys.modules["rapidocr"] = _fake_rapidocr

from ai.core import ocr_client  # noqa: E402 — 위 sys.modules 모킹 이후 import


@pytest.fixture(autouse=True)
def _clear_engine_cache():
    """get_ocr_engine은 @lru_cache라 테스트 간 인스턴스가 공유되면 안 된다."""
    ocr_client.get_ocr_engine.cache_clear()
    yield
    ocr_client.get_ocr_engine.cache_clear()


@patch("rapidocr.RapidOCR")
def test_get_ocr_engine_disables_cls_and_uses_ppocrv5_korean(mock_rapidocr_cls, tmp_path, monkeypatch):
    """P1 회귀 방지 핵심 검증 — Global.use_cls는 항상 False, Rec 설정은 PP-OCRv5 한국어여야 한다."""
    model_root_dir = tmp_path / "rapidocr"
    monkeypatch.setattr(ocr_client, "RAPIDOCR_MODEL_ROOT_DIR", str(model_root_dir))
    mock_rapidocr_cls.return_value = MagicMock()

    ocr_client.get_ocr_engine()

    mock_rapidocr_cls.assert_called_once()
    _args, kwargs = mock_rapidocr_cls.call_args
    params = kwargs["params"]
    assert params["Global.use_cls"] is False
    assert params["Global.model_root_dir"] == str(model_root_dir)
    assert params["Rec.engine_type"] == "onnxruntime"
    assert params["Rec.lang_type"] == "korean"
    assert params["Rec.ocr_version"] == "PP-OCRv5"
    assert params["Rec.model_type"] == "mobile"


@patch("rapidocr.RapidOCR")
def test_get_ocr_engine_creates_model_root_dir_before_init(mock_rapidocr_cls, tmp_path, monkeypatch):
    """모델 캐시 디렉터리는 RapidOCR 생성 전에 미리 만들어져 있어야 한다(hf_cache 볼륨 하위
    서브디렉터리는 최초 실행 시 없으므로 이 함수가 직접 생성해야 함 — fastapi(uid 999) 권한
    문제 방지, EasyOCR 시절 #605 P1 재현과 동일 원칙)."""
    model_root_dir = tmp_path / "rapidocr"
    monkeypatch.setattr(ocr_client, "RAPIDOCR_MODEL_ROOT_DIR", str(model_root_dir))
    mock_rapidocr_cls.return_value = MagicMock()

    assert not model_root_dir.exists()

    ocr_client.get_ocr_engine()

    assert model_root_dir.is_dir()


@patch("rapidocr.RapidOCR")
def test_get_ocr_engine_caches_single_instance(mock_rapidocr_cls, tmp_path, monkeypatch):
    """lru_cache로 프로세스당 1회만 RapidOCR을 생성해야 한다(모델 재로드 비용 상각)."""
    monkeypatch.setattr(ocr_client, "RAPIDOCR_MODEL_ROOT_DIR", str(tmp_path / "rapidocr"))
    mock_rapidocr_cls.return_value = MagicMock()

    first = ocr_client.get_ocr_engine()
    second = ocr_client.get_ocr_engine()

    assert first is second
    mock_rapidocr_cls.assert_called_once()
