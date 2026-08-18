"""#1645 — HF Hub 모델 가중치 revision 고정 회귀 테스트.

yolo_client.get_yolo_model()/unet_client.get_crack_model()이 `YOLO_REVISION`/`UNET_REVISION`
env 설정 시 hf_hub_download(revision=...)로 그대로 전달하는지, 미설정 시 revision=None(현행
main HEAD 폴백) + 경고 로그 1줄을 남기는지 고정한다. 실제 다운로드(HF Hub)는 건드리지 않는다 —
hf_hub_download/모델 생성자를 모두 페이크로 교체한다(test_unet_client.py의 "실 체크포인트는
건드리지 않는다" 원칙과 동일).
"""
import logging
from unittest.mock import MagicMock, patch

import pytest

from ai.core import unet_client, yolo_client


@pytest.fixture(autouse=True)
def _clear_model_caches():
    """@lru_cache(get_yolo_model/get_crack_model)가 테스트 간 결과를 들고 있으면 이후 테스트가
    실제로 hf_hub_download를 호출하지 않고 캐시 히트로 넘어가 revision 전달을 검증하지 못한다."""
    yolo_client.get_yolo_model.cache_clear()
    unet_client.get_crack_model.cache_clear()
    yield
    yolo_client.get_yolo_model.cache_clear()
    unet_client.get_crack_model.cache_clear()


class TestYoloRevisionPinning:
    def test_env_설정시_revision을_hf_hub_download에_그대로_전달한다(self, monkeypatch):
        monkeypatch.setenv("YOLO_REVISION", "abc1234")

        with patch(
            "huggingface_hub.hf_hub_download", return_value="/fake/spalling.pt"
        ) as mock_download, patch("ultralytics.YOLO", return_value=MagicMock()):
            yolo_client.get_yolo_model("SPALLING")

        mock_download.assert_called_once()
        assert mock_download.call_args.kwargs["revision"] == "abc1234"
        assert mock_download.call_args.kwargs["filename"] == "spalling_yolov8n_seg.pt"

    def test_env_미설정시_revision_none으로_main_head에_폴백한다(self, monkeypatch, caplog):
        monkeypatch.delenv("YOLO_REVISION", raising=False)

        with patch(
            "huggingface_hub.hf_hub_download", return_value="/fake/rebar.pt"
        ) as mock_download, patch("ultralytics.YOLO", return_value=MagicMock()):
            with caplog.at_level(logging.WARNING, logger="ai.core.yolo_client"):
                yolo_client.get_yolo_model("REBAR_EXPOSURE")

        assert mock_download.call_args.kwargs["revision"] is None
        assert any(
            "unpinned" in record.message.lower() for record in caplog.records
        ), "revision 미고정 시 경고 로그가 없음"

    def test_토큰은_경고로그에_노출되지_않는다(self, monkeypatch, caplog):
        monkeypatch.delenv("YOLO_REVISION", raising=False)
        monkeypatch.setenv("HF_API_TOKEN", "hf_supersecrettoken12345")

        with patch(
            "huggingface_hub.hf_hub_download", return_value="/fake/spalling.pt"
        ), patch("ultralytics.YOLO", return_value=MagicMock()):
            with caplog.at_level(logging.WARNING, logger="ai.core.yolo_client"):
                yolo_client.get_yolo_model("SPALLING")

        assert "hf_supersecrettoken12345" not in caplog.text


class TestUnetRevisionPinning:
    def test_env_설정시_revision을_hf_hub_download에_그대로_전달한다(self, monkeypatch):
        monkeypatch.setenv("UNET_REVISION", "def5678")

        with patch(
            "huggingface_hub.hf_hub_download", return_value="/fake/crack.pt"
        ) as mock_download, patch(
            "segmentation_models_pytorch.Unet", return_value=MagicMock()
        ), patch("torch.load", return_value={}):
            unet_client.get_crack_model()

        mock_download.assert_called_once()
        assert mock_download.call_args.kwargs["revision"] == "def5678"
        assert mock_download.call_args.kwargs["filename"] == unet_client.CRACK_CHECKPOINT_FILENAME

    def test_env_미설정시_revision_none으로_main_head에_폴백한다(self, monkeypatch, caplog):
        monkeypatch.delenv("UNET_REVISION", raising=False)

        with patch(
            "huggingface_hub.hf_hub_download", return_value="/fake/crack.pt"
        ) as mock_download, patch(
            "segmentation_models_pytorch.Unet", return_value=MagicMock()
        ), patch("torch.load", return_value={}):
            with caplog.at_level(logging.WARNING, logger="ai.core.unet_client"):
                unet_client.get_crack_model()

        assert mock_download.call_args.kwargs["revision"] is None
        assert any(
            "unpinned" in record.message.lower() for record in caplog.records
        ), "revision 미고정 시 경고 로그가 없음"
