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
import json
import time
from unittest.mock import MagicMock, patch

import pytest
import zstandard
from langsmith import run_trees as ls_run_trees
from langsmith import utils as ls_utils
from langsmith.client import Client
from langsmith.run_helpers import tracing_context

SENSITIVE_INPUT = "사업자등록번호 123-45-67890 대표자 홍길동"
SENSITIVE_OUTPUT = "원인은 콘크리트 중성화로 추정됩니다"
FAKE_LANGSMITH_KEY = "-".join(("test", "dummy", "langsmith", "key", "not", "a", "real", "secret"))
FAKE_HF_TOKEN = "-".join(("test", "dummy", "hf", "token", "not", "a", "real", "secret"))


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


def test_ocr_chain_tracing_context_actually_suppresses_transmission(monkeypatch):
    """OCR 체인의 tracing_context(enabled=False) 블록이 실제로 전송을 억제하는지 검증.

    이 테스트는 두 가지를 연속으로 검증한다:
    1. OCR 체인을 실행하면 LangSmith로 **아무것도 전송되지 않는다** (블록 내 LLM 호출).
    2. 대조군(제약 없는 LLM 호출)은 같은 조건에서 **실제로 전송된다** (억제가 작동함을 증명).

    대조군이 없으면 "원래 아무것도 안 잡혀서 통과"와 "억제됐기 때문에 통과"를 구분할 수 없다.
    """
    monkeypatch.setenv("LANGCHAIN_API_KEY", FAKE_LANGSMITH_KEY)
    monkeypatch.setenv("LANGCHAIN_ENDPOINT", "https://api.smith.langchain.com")
    monkeypatch.setenv("LANGCHAIN_PROJECT", "tracing-exclusion-test")
    # HIDE env는 이 테스트의 관심사가 아니다 — tracing_context(enabled=False)는 HIDE 설정과
    # 무관하게 전송 자체를 막는다. true로 둬서 "마스킹까지 겹쳐도 여전히 미전송"임을 함께 보인다.
    monkeypatch.setenv("LANGSMITH_HIDE_INPUTS", "true")
    monkeypatch.setenv("LANGSMITH_HIDE_OUTPUTS", "true")
    # OCR 체인은 get_llm()을 그대로 통과시켜야(mock하지 않아야) 실제 HFInferenceChatModel이
    # LangChain 콜백/트레이싱 경로에 실제로 올라탄다 — get_llm 자체를 mock하면 LangSmith
    # 트레이서가 아예 개입하지 않아 "tracing_context가 억제했다"는 걸 증명할 수 없다.
    # 그래서 get_llm()이 내부적으로 읽는 env(HF_API_TOKEN·REDIS_URL)도 실제로 채워줘야 한다.
    # REDIS_URL은 존재하지 않는 주소를 줘도 된다 — llm_client.py가 RedisError를 캐시 미스로
    # 흡수하도록 이미 방어돼 있다(연결 실패 → 캐시 스킵, 응답 자체는 정상 진행).
    monkeypatch.setenv("HF_API_TOKEN", FAKE_HF_TOKEN)
    monkeypatch.setenv("REDIS_URL", "redis://127.0.0.1:1/0")

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

    # OCR 체인은 with_structured_output(BusinessLicenseOcrExtract)로 파싱하므로 응답이 그
    # 스키마에 맞는 JSON이어야 한다(자유 텍스트를 주면 PydanticOutputParser가 파싱 실패로
    # 재시도를 반복하다 예외를 던져 LLM 호출까지는 도달했는지 자체를 가릴 수 없게 된다).
    # SENSITIVE_INPUT 문자열 자체는 프롬프트(OCR 원문)에 실리므로, 여기서는 응답에 별도로
    # 담지 않아도 "입력이 전송되는지"라는 이 테스트의 목적은 충분히 검증된다.
    ocr_hf_response = MagicMock()
    ocr_hf_response.choices[0].message.content = json.dumps(
        {
            "business_registration_number": "123-45-67890",
            "company_name": "테스트회사",
            "representative_name": "김테스트",
            "business_start_date": "2020.01.15",
        },
        ensure_ascii=False,
    )
    ocr_hf_response.choices[0].finish_reason = "stop"
    ocr_hf_response.usage.prompt_tokens = 10
    ocr_hf_response.usage.completion_tokens = 5
    ocr_hf_response.usage.total_tokens = 15

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
    ) as mock_get_ocr_engine, patch(
        "ai.chains.business_license_ocr_chain._decode_image"
    ) as mock_decode:
        mock_inference.return_value.chat_completion.return_value = ocr_hf_response
        # get_ocr_engine()이 엔진을 반환하고, 그 엔진을 호출(engine(image_bytes))해야 결과가
        # 나온다 — txts/scores는 엔진 "호출 결과"에 실어야 한다(엔진 자체에 실으면 코드가
        # engine(image_bytes)로 얻는 result는 별개의 빈 MagicMock이 되어 zip()이 조용히
        # 빈 리스트를 만들고, "텍스트 없음" 조기 반환 경로로 새 LLM을 아예 호출 안 하게 된다 —
        # 그 상태에서도 captured_ocr가 비어있어 이 테스트가 "억제됐다"고 오판할 뻔했다).
        mock_engine = MagicMock()
        mock_engine.return_value = MagicMock(txts=[SENSITIVE_INPUT], scores=[0.95])
        mock_get_ocr_engine.return_value = mock_engine
        mock_decode.return_value = b"fake-image"

        from ai.chains.business_license_ocr_chain import run_business_license_ocr_chain

        with tracing_context(enabled=True):  # 트레이싱 켜짐 상태를 시뮬레이션
            result = run_business_license_ocr_chain("aGVsbG8=")  # base64로 인코딩된 "hello"

        # LLM 호출까지 실제로 도달했는지 확인 — 이게 없으면 조기 반환(OCR 텍스트 없음 등)으로
        # LLM을 아예 안 불러서 페이로드가 비어있는 것과, tracing_context가 억제한 것을
        # 구분할 수 없다.
        assert result.business_registration_number == "123-45-67890", (
            "OCR 체인이 LLM 호출까지 도달하지 못했다(mock 설정 오류) — "
            "이 경우 아래 '전송 안 됨' 단언이 tracing_context 억제 때문인지 "
            "애초에 호출을 안 해서인지 구분할 수 없어 검증이 무효화된다"
        )

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
