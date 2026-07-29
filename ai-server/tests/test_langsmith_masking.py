"""LangSmith 입출력 마스킹이 민감 정보를 실제로 차단하는지 검증 (#1240).

#1239에서는 체인마다 `tracing_context(enabled=False)`로 트레이싱 자체를 껐지만, 그러면
트레이스가 0건이 되어 도입 목적(사용량·지연·실패 추적)이 사라졌다. 대신 전역 입출력 마스킹
(`LANGSMITH_HIDE_INPUTS` / `LANGSMITH_HIDE_OUTPUTS`)으로 **내용만** 차단하고 구조는 남긴다.

이 테스트는 실제 전송 페이로드를 가로채서 검증한다 — env를 읽는지만 확인하면 "설정은 됐는데
실제로는 새는" 경우를 못 잡기 때문이다. 전송 직전 단계(`Client.request_with_retries`)를
가로채 멀티파트 본문에 민감 문자열이 들어있는지 직접 본다.

conftest.py가 테스트 프로세스의 LANGCHAIN_TRACING_V2를 false로 강제하므로, 각 테스트는
`tracing_context(enabled=True)`로 "켜진 상태"를 재현한다.
"""
import time
from unittest.mock import MagicMock, patch

import pytest
from langsmith import run_trees as ls_run_trees
from langsmith import utils as ls_utils
from langsmith.client import Client
from langsmith.run_helpers import tracing_context

SENSITIVE_INPUT = "사업자등록번호 123-45-67890 대표자 홍길동"
SENSITIVE_OUTPUT = "원인은 콘크리트 중성화로 추정됩니다"
FAKE_HF_TOKEN = "hf_TESTONLY_NOT_A_REAL_TOKEN"


@pytest.fixture(autouse=True)
def _reset_langsmith_process_state():
    """langsmith가 프로세스 단위로 캐싱하는 두 가지를 테스트마다 초기화한다.

    1. `utils.get_env_var` — LRU 캐시라 env를 바꿔도 첫 읽기 값이 고정된다.
    2. `run_trees._CLIENT` — 전역 싱글턴 Client. 첫 트레이스 때 생성되며 그때의 마스킹 설정을
       그대로 유지하므로, 초기화하지 않으면 앞 테스트의 설정이 뒤 테스트로 새어 들어온다.

    운영 함의: 마스킹 설정은 **프로세스 기동 시점에 고정**된다. env를 바꾸려면 컨테이너를
    재기동해야 하며, 돌고 있는 프로세스에 나중에 켜는 것은 효과가 없다.
    """
    def _reset():
        ls_utils.get_env_var.cache_clear()
        ls_run_trees._CLIENT = None

    _reset()
    yield
    _reset()


def _run_and_capture_payload(monkeypatch, *, hide: bool) -> bytes:
    """LLM을 1회 호출하고, LangSmith로 나가려던 멀티파트 본문을 그대로 돌려준다.

    실제 네트워크 전송은 막는다(예외로 중단) — 페이로드만 확보하면 충분하고, 테스트가 외부
    서비스에 의존하면 안 되기 때문이다.
    """
    monkeypatch.setenv("LANGCHAIN_API_KEY", "lsv2_pt_testonly")
    monkeypatch.setenv("LANGCHAIN_ENDPOINT", "https://api.smith.langchain.com")
    monkeypatch.setenv("LANGCHAIN_PROJECT", "masking-test")
    monkeypatch.setenv("LANGSMITH_HIDE_INPUTS", "true" if hide else "false")
    monkeypatch.setenv("LANGSMITH_HIDE_OUTPUTS", "true" if hide else "false")

    captured: list[bytes] = []

    def _spy(self, method, path, **kwargs):
        data = kwargs.get("request_kwargs", {}).get("data") or kwargs.get("data")
        if data:
            captured.append(data if isinstance(data, bytes) else str(data).encode("utf-8"))
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
            result = model.invoke(SENSITIVE_INPUT)
        assert result.content == SENSITIVE_OUTPUT, "마스킹 설정이 LLM 응답 자체를 바꾸면 안 된다"
        # 트레이스 전송은 백그라운드 스레드에서 일어나므로 잠시 대기한다.
        time.sleep(3)

    return b"".join(captured)


def test_masking_off_actually_sends_content(monkeypatch):
    """대조군 — 마스킹을 끄면 내용이 실제로 전송된다.

    이 테스트가 없으면 아래 차단 검증이 "원래 아무것도 안 보내서" 통과한 것인지
    구분할 수 없다.
    """
    payload = _run_and_capture_payload(monkeypatch, hide=False)

    assert payload, "전송 페이로드를 가로채지 못했다 — 검증 자체가 성립하지 않는다"
    assert SENSITIVE_INPUT.encode("utf-8") in payload, (
        "마스킹을 껐는데도 입력이 전송되지 않았다 — 이 테스트의 전제가 깨졌다"
    )


def test_masking_blocks_prompt_and_response_content(monkeypatch):
    """마스킹을 켜면 프롬프트·응답 내용이 전송되지 않아야 한다."""
    payload = _run_and_capture_payload(monkeypatch, hide=True)

    assert payload, "전송 페이로드를 가로채지 못했다 — 검증 자체가 성립하지 않는다"
    assert SENSITIVE_INPUT.encode("utf-8") not in payload, (
        "프롬프트 내용이 LangSmith로 전송된다(개인정보 유출)"
    )
    assert SENSITIVE_OUTPUT.encode("utf-8") not in payload, (
        "LLM 응답 내용이 LangSmith로 전송된다(개인정보 유출)"
    )


def test_hf_api_token_never_leaves(monkeypatch):
    """HF API 토큰은 마스킹 여부와 무관하게 절대 전송되면 안 된다.

    `HFInferenceChatModel.hf_api_token`은 SecretStr이라 직렬화 시 마스킹되는데(#438),
    그 보호가 유지되는지 확인한다 — 토큰은 `extra.invocation_params`에 실릴 수 있고
    이 필드는 HIDE_INPUTS/HIDE_OUTPUTS 대상이 아니라 별도 가드가 필요하다.
    """
    for hide in (True, False):
        payload = _run_and_capture_payload(monkeypatch, hide=hide)
        assert FAKE_HF_TOKEN.encode("utf-8") not in payload, (
            f"HF API 토큰이 LangSmith로 전송된다 (hide={hide})"
        )


def test_masking_keeps_structural_trace_info(monkeypatch):
    """마스킹해도 추적 목적에 필요한 구조 정보는 남아야 한다.

    내용을 가리는 대신 "언제 어떤 모델이 몇 번 호출됐는지"는 남아야 도입 목적
    (사용량·지연·실패 추적)이 성립한다. 전부 가려버리면 트레이싱을 켤 이유가 없다.
    """
    payload = _run_and_capture_payload(monkeypatch, hide=True)

    assert b"Qwen/Qwen3-8B" in payload, "모델명이 남지 않으면 어떤 모델을 썼는지 알 수 없다"
    assert b"start_time" in payload and b"end_time" in payload, (
        "실행 시각이 남지 않으면 지연시간을 추적할 수 없다"
    )


@pytest.mark.parametrize("env_value", ["true", "false"])
def test_masking_env_is_honored_by_client(monkeypatch, env_value):
    """Client가 env를 실제로 읽는지 확인 — 오타·이름 변경 조기 감지용."""
    monkeypatch.setenv("LANGSMITH_HIDE_INPUTS", env_value)
    monkeypatch.setenv("LANGSMITH_HIDE_OUTPUTS", env_value)

    client = Client(api_key="lsv2_pt_testonly")

    expected = env_value == "true"
    assert client._hide_inputs is expected
    assert client._hide_outputs is expected
