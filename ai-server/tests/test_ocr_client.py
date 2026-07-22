"""ai/core/ocr_client.py 단위 테스트 (#605) — `get_ocr_engine()`이 `easyocr.Reader`를
올바른 인자로 호출하는지 검증한다. 실제 모델 다운로드/추론(Reader 인스턴스화 내부 동작)은
하지 않는다 — `easyocr.Reader` 클래스 자체를 모킹한다.

## P1 회귀 방지 (코드 리뷰 재현, docker에서 재현됨)
`user_network_directory`를 지정하지 않으면 EasyOCR이 기본 경로(`~/.EasyOCR/user_network` →
컨테이너에선 fastapi 유저 HOME=`/app` 기준 `/app/.EasyOCR/user_network`)를 mkdir하려 시도하는데,
`/app`은 root:root 755라 fastapi(uid 999)가 mkdir에 실패해 **첫 OCR 요청이 PermissionError로
무조건 실패한다**. 이 파일은 `user_network_directory`가 항상 쓰기 가능한 경로(hf_cache 볼륨
하위)로 지정되는지, 그리고 그 디렉터리가 실제로 사전 생성되는지를 고정한다.

`easyocr`는 import 시 torch/torchvision을 로드하는데, 실제 로드하면 종료 시 세그폴트를 유발하므로
(아래 import 블록 주석 참조) 이 파일은 `sys.modules`에 가짜 easyocr를 심어 실제 로드를 회피한다.
앱/체인 import 경로는 원래 easyocr를 요구하지 않는다(지연 import #573, `test_business_license_ocr.py`가 모킹 검증).
"""
import sys
from unittest.mock import MagicMock, patch

import pytest  # noqa: F401 — fixture 데코레이터에서 사용

# ⚠️ easyocr는 import 시 torch/torchvision을 로드하는데, 이를 실제로 로드하면 같은 프로세스의
# chromadb/onnxruntime 등과 함께 **pytest 종료 시 Segmentation fault**(native 라이브러리 정리
# 순서 충돌)를 유발한다 — PR머신 docker build+test에서 "167 passed … Segmentation fault"로 재현(#605 회귀).
# 이 테스트는 get_ocr_engine()이 easyocr.Reader를 올바른 인자로 호출하는지만 검증하므로, 실제
# easyocr를 import하지 않고 sys.modules에 가짜 모듈을 심는다(torch/torchvision 미로드 → 세그폴트 회피).
# get_ocr_engine 내부의 지연 `import easyocr`도 이 가짜를 받는다.
sys.modules["easyocr"] = MagicMock()

from ai.core import ocr_client  # noqa: E402 — 위 sys.modules 모킹 이후 import


@pytest.fixture(autouse=True)
def _clear_engine_cache():
    """get_ocr_engine은 @lru_cache라 테스트 간 인스턴스가 공유되면 안 된다."""
    ocr_client.get_ocr_engine.cache_clear()
    yield
    ocr_client.get_ocr_engine.cache_clear()


@patch("easyocr.Reader")
def test_get_ocr_engine_passes_user_network_directory(mock_reader_cls, tmp_path, monkeypatch):
    """P1 회귀 방지 핵심 검증 — Reader 생성 시 user_network_directory가 항상 전달돼야 한다."""
    model_dir = tmp_path / "easyocr"
    user_network_dir = model_dir / "user_network"
    monkeypatch.setattr(ocr_client, "EASYOCR_MODEL_DIR", str(model_dir))
    monkeypatch.setattr(ocr_client, "EASYOCR_USER_NETWORK_DIR", str(user_network_dir))
    mock_reader_cls.return_value = MagicMock()

    ocr_client.get_ocr_engine()

    mock_reader_cls.assert_called_once()
    _args, kwargs = mock_reader_cls.call_args
    assert kwargs["model_storage_directory"] == str(model_dir)
    assert kwargs["user_network_directory"] == str(user_network_dir)
    assert kwargs["gpu"] is False
    assert kwargs["download_enabled"] is True


@patch("easyocr.Reader")
def test_get_ocr_engine_creates_model_and_user_network_dirs_before_reader_init(
    mock_reader_cls, tmp_path, monkeypatch
):
    """model_storage_directory·user_network_directory 둘 다 Reader 생성 전에 미리 만들어져
    있어야 한다(둘 다 hf_cache 볼륨 하위라 상위 디렉터리는 fastapi 소유지만, 하위 서브디렉터리는
    최초 실행 시 없으므로 이 함수가 직접 생성해야 함 — P1 재현의 근본 원인)."""
    model_dir = tmp_path / "easyocr"
    user_network_dir = model_dir / "user_network"
    monkeypatch.setattr(ocr_client, "EASYOCR_MODEL_DIR", str(model_dir))
    monkeypatch.setattr(ocr_client, "EASYOCR_USER_NETWORK_DIR", str(user_network_dir))
    mock_reader_cls.return_value = MagicMock()

    assert not model_dir.exists()

    ocr_client.get_ocr_engine()

    assert model_dir.is_dir()
    assert user_network_dir.is_dir()


@patch("easyocr.Reader")
def test_get_ocr_engine_caches_single_instance(mock_reader_cls, tmp_path, monkeypatch):
    """lru_cache로 프로세스당 1회만 Reader를 생성해야 한다(모델 재로드 비용 상각)."""
    monkeypatch.setattr(ocr_client, "EASYOCR_MODEL_DIR", str(tmp_path / "easyocr"))
    monkeypatch.setattr(
        ocr_client, "EASYOCR_USER_NETWORK_DIR", str(tmp_path / "easyocr" / "user_network")
    )
    mock_reader_cls.return_value = MagicMock()

    first = ocr_client.get_ocr_engine()
    second = ocr_client.get_ocr_engine()

    assert first is second
    mock_reader_cls.assert_called_once()
