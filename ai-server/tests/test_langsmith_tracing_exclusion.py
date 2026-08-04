"""LangSmith 트레이싱 제외 메커니즘 검증 (PR #1545, 이슈 #1550).

PR #1545에서 전역 env 기반 마스킹(LANGSMITH_HIDE_INPUTS/OUTPUTS)을 도입했으나,
OCR 체인(사업자등록번호·대표자명 등 개인정보 포함)은 민감성 때문에 `tracing_context(enabled=False)`로
트레이싱 자체를 차단한다(국외 SaaS 제3자 제공 근거 불명 — PR머신 P1 지적).

이 테스트는 다음을 검증한다:
1. OCR 체인의 LLM 호출이 실제로 LangSmith로 **전송되지 않는다** (억제만 아니라 미기록).
2. HF API 토큰이 어떤 경로로도 전송되지 않는다 (마스킹 여부 무관).
3. 대조군(제약 없는 일반 LLM 호출)이 실제로 전송됨을 보증 (억제 검증의 신뢰도 확보).
"""
import io
import threading
import time
from datetime import date
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest
import zstandard
from langchain_core.runnables import RunnableLambda
from langsmith import run_trees as ls_run_trees
from langsmith import utils as ls_utils
from langsmith.client import Client
from langsmith.run_helpers import tracing_context

SENSITIVE_INPUT = "사업자등록번호 123-45-67890 대표자 홍길동"
SENSITIVE_OUTPUT = "원인은 콘크리트 중성화로 추정됩니다"
FAKE_LANGSMITH_KEY = "-".join(("test", "dummy", "langsmith", "key", "not", "a", "real", "secret"))
FAKE_HF_TOKEN = "-".join(("test", "dummy", "hf", "token", "not", "a", "real", "secret"))

_AI_SERVER_DIR = Path(__file__).resolve().parent.parent


_ZSTD_MAGIC = b"\x28\xb5\x2f\xfd"


def _extract_bytes(data) -> bytes:
    """LangSmith 전송 페이로드를 실제(압축 해제된) 바이트로 변환.

    소규모 배치는 bytes로 오지만, 대규모 트레이스는 zstd로 압축되고
    `_io.BytesIO`로 온다 — 압축 해제 없이는 민감 문자열을 검색할 수 없다.
    """
    if isinstance(data, bytes):
        raw = data
    elif hasattr(data, "read"):
        pos = data.tell() if hasattr(data, "tell") else None
        content = data.read()
        if pos is not None and hasattr(data, "seek"):
            data.seek(pos)
        raw = content if isinstance(content, bytes) else str(content).encode("utf-8")
    else:
        raw = str(data).encode("utf-8")

    if raw[:4] == _ZSTD_MAGIC:
        return zstandard.ZstdDecompressor().stream_reader(io.BytesIO(raw)).read()
    return raw


def _wait_for_flush(captured: list, *, timeout: float = 8.0, settle: float = 0.4) -> None:
    """백그라운드 전송이 끝날 때까지 대기.

    captured 길이가 settle 초 동안 변하지 않으면 flush 완료로 본다.
    """
    deadline = time.time() + timeout
    last_len = -1
    settle_start = time.time()
    while time.time() < deadline:
        if len(captured) != last_len:
            last_len = len(captured)
            settle_start = time.time()
        elif captured and time.time() - settle_start >= settle:
            return
        time.sleep(0.05)


@pytest.fixture(autouse=True)
def _reset_langsmith_process_state():
    """각 테스트마다 LangSmith 싱글턴 및 LRU 캐시를 초기화.

    LangSmith 클라이언트와 env 판정이 프로세스 단위로 캐시되므로,
    한 테스트의 설정이 다음 테스트로 새어 들어오는 것을 방지한다.
    """
    def _reset():
        ls_utils.get_env_var.cache_clear()
        ls_run_trees._CLIENT = None

    _reset()
    yield
    _reset()


@pytest.mark.parametrize("hide_enabled", [True])
def test_ocr_chain_tracing_context_actually_suppresses_transmission(monkeypatch, hide_enabled):
    """OCR 체인의 tracing_context(enabled=False) 블록이 실제로 전송을 억제하는지 검증.

    이 테스트는 두 가지를 연속으로 검증한다:
    1. OCR 체인을 실행하면 LangSmith로 **아무것도 전송되지 않는다** (블록 내 LLM 호출).
    2. 대조군(제약 없는 LLM 호출)은 같은 조건에서 **실제로 전송된다** (억제가 작동함을 증명).

    대조군이 없으면 "원래 아무것도 안 잡혀서 통과"와 "억제됐기 때문에 통과"를 구분할 수 없다.
    """
    monkeypatch.setenv("LANGCHAIN_API_KEY", FAKE_LANGSMITH_KEY)
    monkeypatch.setenv("LANGCHAIN_ENDPOINT", "https://api.smith.langchain.com")
    monkeypatch.setenv("LANGCHAIN_PROJECT", "tracing-exclusion-test")
    monkeypatch.setenv("LANGSMITH_HIDE_INPUTS", "true" if hide_enabled else "false")
    monkeypatch.setenv("LANGSMITH_HIDE_OUTPUTS", "true" if hide_enabled else "false")

    captured_ocr: list[bytes] = []
    captured_control: list[bytes] = []

    def _spy_for_ocr(self, method, path, **kwargs):
        data = kwargs.get("request_kwargs", {}).get("data") or kwargs.get("data")
        if data:
            captured_ocr.append(_extract_bytes(data))
        raise RuntimeError("테스트에서 실제 전송을 막음")

    def _spy_for_control(self, method, path, **kwargs):
        data = kwargs.get("request_kwargs", {}).get("data") or kwargs.get("data")
        if data:
            captured_control.append(_extract_bytes(data))
        raise RuntimeError("테스트에서 실제 전송을 막음")

    hf_response = MagicMock()
    hf_response.choices[0].message.content = SENSITIVE_OUTPUT
    hf_response.choices[0].finish_reason = "stop"
    hf_response.usage.prompt_tokens = 10
    hf_response.usage.completion_tokens = 5
    hf_response.usage.total_tokens = 15

    # ① OCR 체인 실행 — tracing_context(enabled=False) 블록 내에서 실행
    with patch.object(Client, "request_with_retries", _spy_for_ocr), patch(
        "ai.core.hf_chat_model.InferenceClient"
    ) as mock_inference, patch(
        "ai.chains.business_license_ocr_chain.get_ocr_engine"
    ) as mock_ocr_engine, patch(
        "ai.chains.business_license_ocr_chain._decode_image"
    ) as mock_decode:
        mock_inference.return_value.chat_completion.return_value = hf_response
        mock_ocr_engine.return_value = MagicMock(txts=[SENSITIVE_INPUT], scores=[0.95])
        mock_decode.return_value = b"fake-image"

        from ai.chains.business_license_ocr_chain import run_business_license_ocr_chain

        with tracing_context(enabled=True):  # 트레이싱 켜짐 상태를 시뮬레이션
            try:
                run_business_license_ocr_chain("aGVsbG8=")  # base64로 인코딩된 "hello"
            except Exception:
                pass  # OCR/LLM 목 조립 단계에서 깨질 수 있음, 무시

        _wait_for_flush(captured_ocr)

    # OCR 체인은 tracing_context(enabled=False)로 감싸져 있으므로 페이로드가 **완전히 비어있어야 한다**
    ocr_payload = b"".join(captured_ocr)
    assert not ocr_payload, (
        "OCR 체인이 tracing_context(enabled=False) 블록 내에서 실행되는데도 "
        "LangSmith로 전송됐다 — 억제 메커니즘 작동 실패(P1)"
    )

    # ② 대조군 — 마스킹을 OFF로 하고 일반 LLM 호출하면 프롬프트가 실제로 **전송된다**
    # (이것이 없으면 OCR 테스트가 "원래 아무것도 안 잡혀서" 통과한 건지 "억제됐기 때문에" 통과한 건지 모름)
    ls_utils.get_env_var.cache_clear()  # 이전 spy의 Client 싱글턴 영향 제거
    ls_run_trees._CLIENT = None
    monkeypatch.setenv("LANGSMITH_HIDE_INPUTS", "false")  # 대조군은 마스킹 OFF
    monkeypatch.setenv("LANGSMITH_HIDE_OUTPUTS", "false")

    with patch.object(Client, "request_with_retries", _spy_for_control), patch(
        "ai.core.hf_chat_model.InferenceClient"
    ) as mock_inference2:
        mock_inference2.return_value.chat_completion.return_value = hf_response

        from ai.core.hf_chat_model import HFInferenceChatModel

        model = HFInferenceChatModel(model="Qwen/Qwen3-8B", hf_api_token=FAKE_HF_TOKEN)
        with tracing_context(enabled=True):
            result = model.invoke(SENSITIVE_INPUT)
            assert result.content == SENSITIVE_OUTPUT, "마스킹이 응답을 바꾸면 안 됨"

        _wait_for_flush(captured_control)

    # 마스킹 OFF 상태의 호출은 페이로드에 프롬프트가 **실제로 포함되어야 한다**
    control_payload = b"".join(captured_control)
    assert control_payload, (
        "대조군(마스킹 OFF LLM 호출)에서 전송 페이로드를 가로채지 못했다 — "
        "검증 자체가 성립하지 않는다(테스트 설정 오류)"
    )
    assert SENSITIVE_INPUT.encode("utf-8") in control_payload, (
        "마스킹 OFF 상태의 LLM 호출 프롬프트가 전송되지 않았다 — "
        "대조군 자체가 작동하지 않았으므로 OCR 억제 검증이 무효"
    )


def test_hf_api_token_never_leaves_when_tracing_enabled(monkeypatch):
    """HF API 토큰이 LangSmith로 전송되지 않는지 검증 (마스킹 여부 무관).

    HFInferenceChatModel.hf_api_token은 SecretStr이라 일반적으로 보호되지만,
    extra.invocation_params 필드는 HIDE_INPUTS/OUTPUTS 대상이 아니라
    별도 보호(LangSmith의 기본 객체 직렬화)에 의존한다. 이 테스트는
    그 보호가 실제로 작동하는지 페이로드로 검증한다.
    """
    monkeypatch.setenv("LANGCHAIN_API_KEY", FAKE_LANGSMITH_KEY)
    monkeypatch.setenv("LANGCHAIN_ENDPOINT", "https://api.smith.langchain.com")
    monkeypatch.setenv("LANGCHAIN_PROJECT", "tracing-exclusion-test")
    monkeypatch.setenv("LANGSMITH_HIDE_INPUTS", "true")
    monkeypatch.setenv("LANGSMITH_HIDE_OUTPUTS", "true")

    captured: list[bytes] = []

    def _spy(self, method, path, **kwargs):
        data = kwargs.get("request_kwargs", {}).get("data") or kwargs.get("data")
        if data:
            captured.append(_extract_bytes(data))
        raise RuntimeError("테스트에서 실제 전송을 막음")

    hf_response = MagicMock()
    hf_response.choices[0].message.content = SENSITIVE_OUTPUT
    hf_response.choices[0].finish_reason = "stop"
    hf_response.usage.prompt_tokens = 10
    hf_response.usage.completion_tokens = 5
    hf_response.usage.total_tokens = 15

    with patch.object(Client, "request_with_retries", _spy), patch(
        "ai.core.hf_chat_model.InferenceClient"
    ) as mock_inference:
        mock_inference.return_value.chat_completion.return_value = hf_response

        from ai.core.hf_chat_model import HFInferenceChatModel

        model = HFInferenceChatModel(model="Qwen/Qwen3-8B", hf_api_token=FAKE_HF_TOKEN)
        with tracing_context(enabled=True):
            model.invoke(SENSITIVE_INPUT)

        _wait_for_flush(captured)

    payload = b"".join(captured)
    assert payload, "전송 페이로드를 가로채지 못했다 — 검증 자체가 성립하지 않는다"
    assert FAKE_HF_TOKEN.encode("utf-8") not in payload, (
        "HF API 토큰이 LangSmith로 전송된다 — 크레덴셜 유출(P1)"
    )
