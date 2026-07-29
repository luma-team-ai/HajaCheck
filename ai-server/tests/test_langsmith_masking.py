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
from langchain_core.runnables import RunnableLambda
from langsmith import run_trees as ls_run_trees
from langsmith import utils as ls_utils
from langsmith.client import Client
from langsmith.run_helpers import tracing_context

from ai.core.langsmith_guard import enforce_masked_tracing, scrub_error_anonymizer

SENSITIVE_INPUT = "사업자등록번호 123-45-67890 대표자 홍길동"
SENSITIVE_OUTPUT = "원인은 콘크리트 중성화로 추정됩니다"
FAKE_HF_TOKEN = "hf_TESTONLY_NOT_A_REAL_TOKEN"


def _wait_for_flush(captured: list, *, timeout: float = 8.0, settle: float = 0.4) -> None:
    """백그라운드 전송이 끝날 때까지 대기 — 고정 sleep 대신 안정화 폴링(P3).

    트레이스는 배치 여러 건(create/update)으로 나뉘어 올 수 있어 "첫 건 도착"만으로는
    이르다. captured 길이가 `settle`초 동안 변하지 않으면 flush 완료로 본다. 상한(timeout)을
    두어 러너가 느려도 오탐 대신 기존 단언("가로채지 못했다")이 원인을 드러내게 한다.
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


def _run_and_capture_payload(monkeypatch, *, hide: bool, error_message: str | None = None) -> bytes:
    """LLM을 1회 호출하고, LangSmith로 나가려던 멀티파트 본문을 그대로 돌려준다.

    실제 네트워크 전송은 막는다(예외로 중단) — 페이로드만 확보하면 충분하고, 테스트가 외부
    서비스에 의존하면 안 되기 때문이다.

    `error_message`를 주면 LLM 호출이 그 메시지를 담은 예외를 던지게 해 **예외 경로**를
    재현한다 — run의 `error` 필드는 HIDE_INPUTS/OUTPUTS 대상이 아니라 별도 검증이 필요하다
    (P1, langsmith_guard.py 모듈 docstring 참고).
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
        if error_message is None:
            mock_inference.return_value.chat_completion.return_value = hf_response
        else:
            mock_inference.return_value.chat_completion.side_effect = RuntimeError(error_message)
        from ai.core.hf_chat_model import HFInferenceChatModel

        model = HFInferenceChatModel(model="Qwen/Qwen3-8B", hf_api_token=FAKE_HF_TOKEN)
        with tracing_context(enabled=True):
            if error_message is None:
                result = model.invoke(SENSITIVE_INPUT)
                assert result.content == SENSITIVE_OUTPUT, "마스킹 설정이 LLM 응답 자체를 바꾸면 안 된다"
            else:
                with pytest.raises(Exception):
                    model.invoke(SENSITIVE_INPUT)
        # 트레이스 전송은 백그라운드 스레드에서 일어난다 — 안정화 폴링으로 대기(P3).
        _wait_for_flush(captured)

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


# ── 예외(error) 경로 — P1 회귀 (#1240 머신 반려 사유) ──────────────────────────────


def test_error_path_leaks_without_anonymizer(monkeypatch):
    """대조군 — anonymizer가 없으면 **마스킹이 켜져 있어도** 예외 메시지가 전송된다.

    HIDE_INPUTS/OUTPUTS는 run의 inputs/outputs만 가리고 error 필드는 대상이 아니다
    (langsmith 0.10.10 실측). 이 대조군이 있어야 아래 차단 테스트의 통과가 "원래 안 새서"가
    아님을 보장한다. 훗날 langsmith가 error를 자체 마스킹하게 되어 이 테스트가 실패하면,
    그때는 anonymizer 가드의 필요성을 재평가하면 된다(좋은 실패).
    """
    payload = _run_and_capture_payload(
        monkeypatch, hide=True,
        error_message=f"structured output 파싱 실패: {SENSITIVE_INPUT} / {SENSITIVE_OUTPUT}",
    )

    assert payload, "전송 페이로드를 가로채지 못했다 — 검증 자체가 성립하지 않는다"
    assert SENSITIVE_INPUT.encode("utf-8") in payload, (
        "예외 메시지가 error 필드로 전송되지 않았다 — 유출 경로 자체가 사라진 것이므로 "
        "langsmith 버전 변경 여부를 확인하라(가드 재평가 신호)"
    )


def test_error_path_masked_with_boot_guard_anonymizer(monkeypatch):
    """P1 회귀 — 부팅 가드가 설치한 anonymizer가 예외 원문을 제거하고 예외 타입만 남긴다.

    프로덕션 부팅 순서 그대로 재현한다: env 세팅 → enforce_masked_tracing()(싱글턴 선점)
    → 이후 트레이스에서 LLM 예외 발생. 이 테스트가 "예외가 나도 원문이 전송되지 않는다"는
    계약을 고정한다 — 가드 호출을 main.py에서 지우면 위 대조군과 같은 결과가 되어 실패한다.
    """
    monkeypatch.setenv("LANGCHAIN_TRACING_V2", "true")
    monkeypatch.setenv("LANGCHAIN_API_KEY", "lsv2_pt_testonly")
    monkeypatch.setenv("LANGCHAIN_ENDPOINT", "https://api.smith.langchain.com")
    monkeypatch.setenv("LANGSMITH_HIDE_INPUTS", "true")
    monkeypatch.setenv("LANGSMITH_HIDE_OUTPUTS", "true")
    enforce_masked_tracing()

    payload = _run_and_capture_payload(
        monkeypatch, hide=True,
        error_message=f"structured output 파싱 실패: {SENSITIVE_INPUT} / {SENSITIVE_OUTPUT}",
    )

    assert payload, "전송 페이로드를 가로채지 못했다 — 검증 자체가 성립하지 않는다"
    assert SENSITIVE_INPUT.encode("utf-8") not in payload, (
        "예외 메시지의 OCR 원문이 error 필드로 LangSmith에 전송된다(개인정보 유출, P1)"
    )
    assert SENSITIVE_OUTPUT.encode("utf-8") not in payload, (
        "예외 메시지의 LLM 응답 내용이 error 필드로 LangSmith에 전송된다(개인정보 유출, P1)"
    )
    assert b"RuntimeError" in payload, (
        "예외 타입까지 사라지면 실패 추적(트레이싱 도입 목적)이 불가능하다 — 스크럽이 과하다"
    )


def test_scrub_error_anonymizer_keeps_type_drops_content():
    """anonymizer 단위 계약 — 예외 타입명은 남기고 메시지·트레이스백은 버린다."""
    scrubbed = scrub_error_anonymizer(
        {"error": f"OutputParserException('{SENSITIVE_INPUT}')\nTraceback (most recent call last): ..."}
    )
    assert "OutputParserException" in scrubbed["error"]
    assert SENSITIVE_INPUT not in scrubbed["error"]

    # error 래핑이 아닌 형태(inputs/outputs 경로)가 들어오면 전부 비전송 — env 마스킹과 같은 방향.
    assert scrub_error_anonymizer({"prompt": SENSITIVE_INPUT}) == {}


# ── 부팅 가드 — P2 (fail-open 차단) ──────────────────────────────────────────────


def test_boot_guard_blocks_tracing_without_masking(monkeypatch):
    """트레이싱 ON + 마스킹 env 누락 = 기동 중단 — fail-open 조합을 물리적으로 막는다."""
    monkeypatch.setenv("LANGCHAIN_TRACING_V2", "true")
    monkeypatch.delenv("LANGSMITH_HIDE_INPUTS", raising=False)
    monkeypatch.delenv("LANGSMITH_HIDE_OUTPUTS", raising=False)

    with pytest.raises(RuntimeError, match="LANGSMITH_HIDE"):
        enforce_masked_tracing()


def test_boot_guard_blocks_partial_masking(monkeypatch):
    """둘 중 하나만 true여도 기동 중단 — outputs만 가리고 inputs가 새는 조합 등을 막는다."""
    monkeypatch.setenv("LANGCHAIN_TRACING_V2", "true")
    monkeypatch.setenv("LANGSMITH_HIDE_INPUTS", "true")
    monkeypatch.delenv("LANGSMITH_HIDE_OUTPUTS", raising=False)

    with pytest.raises(RuntimeError, match="LANGSMITH_HIDE_OUTPUTS"):
        enforce_masked_tracing()


_FRAMEWORK_TRACING_ENV_NAMES = (
    "LANGCHAIN_TRACING_V2", "LANGSMITH_TRACING", "LANGSMITH_TRACING_V2", "LANGCHAIN_TRACING",
)


def _clear_tracing_env(monkeypatch):
    for key in _FRAMEWORK_TRACING_ENV_NAMES:
        monkeypatch.delenv(key, raising=False)


def test_boot_guard_noop_when_tracing_off(monkeypatch):
    """트레이싱 OFF면 아무것도 하지 않는다 — 전송이 없으니 유출 표면도 없고, 싱글턴도 안 만든다."""
    _clear_tracing_env(monkeypatch)
    monkeypatch.setenv("LANGCHAIN_TRACING_V2", "false")
    monkeypatch.delenv("LANGSMITH_HIDE_INPUTS", raising=False)
    monkeypatch.delenv("LANGSMITH_HIDE_OUTPUTS", raising=False)

    enforce_masked_tracing()

    assert ls_run_trees._CLIENT is None, "트레이싱 OFF인데 Client 싱글턴을 만들었다(불필요)"


@pytest.mark.parametrize("env_name", _FRAMEWORK_TRACING_ENV_NAMES)
def test_boot_guard_covers_all_framework_tracing_env_names(monkeypatch, env_name):
    """프레임워크가 인식하는 4개 트레이싱 env 이름 어느 것으로 켜도 가드가 발동해야 한다.

    2차 리뷰 P1 회귀 고정 — 초기 구현은 키 2개만 자체 목록으로 봐서 `LANGSMITH_TRACING_V2=true`
    등으로 켜면 가드(기동 차단·error 스크럽)가 통째로 우회됐다. 판정을
    `ls_utils.tracing_is_enabled()` 위임으로 바꿔 키 집합이 구조적으로 일치함을 고정한다.
    """
    _clear_tracing_env(monkeypatch)
    monkeypatch.setenv(env_name, "true")
    monkeypatch.delenv("LANGSMITH_HIDE_INPUTS", raising=False)
    monkeypatch.delenv("LANGSMITH_HIDE_OUTPUTS", raising=False)

    with pytest.raises(RuntimeError, match="LANGSMITH_HIDE"):
        enforce_masked_tracing()


@pytest.mark.parametrize("value", ["1", "yes", "on", "TRUE", "t"])
def test_boot_guard_stays_aligned_with_framework_on_truthy_variants(monkeypatch, value):
    """관례적 truthy 값("1"·"yes" 등)에서 가드와 프레임워크의 판정이 일치해야 한다.

    실측(langsmith 0.10.10): `tracing_is_enabled()`는 소문자 "true"만 인정하므로 이 값들은
    실제로 트레이싱을 켜지 **않는다** — 따라서 가드도 발동하지 않는 게 정합이다(켜지지도 않을
    트레이싱 때문에 기동을 막으면 오탐 fail-closed). 가드가 같은 함수에 위임하므로 구조적으로
    일치하지만, 훗날 langsmith가 truthy 판정을 넓히면 아래 첫 단언이 깨져 이 전제의 만료를
    시끄럽게 알린다(그때는 이 테스트의 기대치를 갱신하면 가드는 위임 덕에 이미 정합이다).
    """
    _clear_tracing_env(monkeypatch)
    monkeypatch.setenv("LANGCHAIN_TRACING_V2", value)
    monkeypatch.delenv("LANGSMITH_HIDE_INPUTS", raising=False)
    monkeypatch.delenv("LANGSMITH_HIDE_OUTPUTS", raising=False)

    assert not ls_utils.tracing_is_enabled(), (
        f"langsmith가 {value!r}를 트레이싱 ON으로 판정하기 시작했다 — 이 테스트의 기대치를 갱신하라"
    )
    enforce_masked_tracing()  # 프레임워크가 OFF로 보는 값이므로 예외 없이 통과해야 한다
    assert ls_run_trees._CLIENT is None


@pytest.mark.parametrize("hide_value", ["TRUE", "True", "1"])
def test_boot_guard_rejects_hide_values_client_ignores(monkeypatch, hide_value):
    """Client가 마스킹 OFF로 해석하는 HIDE 값("TRUE" 등)이면 가드가 기동을 막아야 한다.

    Client는 `get_env_var("HIDE_*") == "true"`로 **소문자 "true"만** 마스킹 ON으로 인식한다
    (실측: HIDE_INPUTS=TRUE -> _hide_inputs=False). 초기 가드는 대소문자를 무시해 이 조합을
    통과시켰다 — "가드는 만족, Client는 마스킹 안 함"이라는 정반대 fail-open. 가드가 Client와
    동일 술어를 쓰는지 여기서 고정한다.
    """
    _clear_tracing_env(monkeypatch)
    monkeypatch.setenv("LANGCHAIN_TRACING_V2", "true")
    monkeypatch.setenv("LANGSMITH_HIDE_INPUTS", hide_value)
    monkeypatch.setenv("LANGSMITH_HIDE_OUTPUTS", hide_value)

    # 전제 고정 — 이 값에서 Client는 실제로 마스킹하지 않는다.
    assert Client(api_key="lsv2_pt_testonly")._hide_inputs is False, (
        f"Client가 {hide_value!r}를 마스킹 ON으로 인식하기 시작했다 — 가드 술어를 재검토하라"
    )
    with pytest.raises(RuntimeError, match="LANGSMITH_HIDE"):
        enforce_masked_tracing()


def test_boot_guard_installs_error_anonymizer(monkeypatch):
    """정상 조합(트레이싱 ON + 마스킹 완전)이면 싱글턴에 error 스크럽이 선점 설치된다."""
    monkeypatch.setenv("LANGCHAIN_TRACING_V2", "true")
    monkeypatch.setenv("LANGCHAIN_API_KEY", "lsv2_pt_testonly")
    monkeypatch.setenv("LANGSMITH_HIDE_INPUTS", "true")
    monkeypatch.setenv("LANGSMITH_HIDE_OUTPUTS", "true")

    enforce_masked_tracing()

    client = ls_run_trees._CLIENT
    assert client is not None, "부팅 가드가 싱글턴을 선점 생성하지 않았다"
    assert client._anonymizer is scrub_error_anonymizer, (
        "싱글턴에 error 스크럽이 실리지 않았다 — 예외 경로(P1) 가드가 무효"
    )


# ── 그래프 체인 경로 — P3 (LangGraph 노드 상태·병렬 워커까지 마스킹 검증) ─────────


class _FakeStructuredLLM:
    """get_llm() 대체 — with_structured_output()이 트레이싱되는 RunnableLambda를 돌려준다.

    MagicMock과 달리 RunnableLambda는 langchain 콜백 체계를 그대로 타므로, LangGraph 노드
    상태·RunnableParallel 워커 스레드의 run이 실제로 LangSmith 전송 큐에 들어간다 — 이
    테스트의 검증 대상이 바로 그 전송 본문이다.
    """

    def with_structured_output(self, _schema, **_kwargs):
        return RunnableLambda(lambda _prompt: MagicMock())


@pytest.mark.parametrize("hide", [False, True], ids=["대조군-전송됨", "마스킹-차단됨"])
def test_report_chain_graph_payload_masking(monkeypatch, hide):
    """그래프 체인(report_chain) 실경로 검증 — 시설명이 노드 상태·프롬프트로 흐르는 전 구간.

    report_chain은 RunnableParallel(스레드 워커) + StateGraph라 중간 상태가 가장 많은 체인이다.
    hide=False 대조군이 민감 문자열의 실제 유입을 증명하고, hide=True가 차단을 고정한다 —
    단일 모델 경로만 보던 기존 테스트의 공백(P3)을 메운다.
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

    # patch()의 pkgutil 경로 해석은 서브모듈을 임포트해주지 않으므로 먼저 임포트해둔다.
    from ai.chains.report_chain import run_report_chain

    with patch.object(Client, "request_with_retries", _spy), patch(
        "ai.chains.report_chain.get_llm", return_value=_FakeStructuredLLM()
    ):
        with tracing_context(enabled=True):
            try:
                run_report_chain(facility_info={"name": SENSITIVE_INPUT}, confirmed_defects=[])
            except Exception:  # noqa: BLE001 — MagicMock 응답이라 조립 단계에서 깨질 수 있다.
                pass  # 검증 대상은 "전송 페이로드"뿐이다.
        _wait_for_flush(captured)

    payload = b"".join(captured)
    assert payload, "전송 페이로드를 가로채지 못했다 — 검증 자체가 성립하지 않는다"
    if hide:
        assert SENSITIVE_INPUT.encode("utf-8") not in payload, (
            "그래프 체인 경로(노드 상태·프롬프트)에서 시설 정보가 LangSmith로 전송된다"
        )
    else:
        assert SENSITIVE_INPUT.encode("utf-8") in payload, (
            "마스킹을 껐는데도 시설 정보가 전송되지 않았다 — 대조군 전제가 깨졌다"
        )
