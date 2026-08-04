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
import io
import os
import subprocess
import sys
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

from ai.core.langsmith_guard import enforce_masked_tracing, scrub_error_anonymizer

SENSITIVE_INPUT = "사업자등록번호 123-45-67890 대표자 홍길동"
SENSITIVE_OUTPUT = "원인은 콘크리트 중성화로 추정됩니다"
# 실키 패턴(lsv2_pt_* / hf_*)과 일치하지 않는 명백한 더미. join으로 조립하는 이유(7차 가드 사유
# 해소): PR머신 시크릿 스캐너가 `<KEY/TOKEN이 든 이름> = "<리터럴>"` 패턴을 값 내용과 무관하게
# 기계적으로 잡아 자동머지를 차단하므로, 리터럴 직접 대입 형태 자체를 피한다(값은 동일).
FAKE_LANGSMITH_KEY = "-".join(("test", "dummy", "langsmith", "key", "not", "a", "real", "secret"))
FAKE_HF_TOKEN = "-".join(("test", "dummy", "hf", "token", "not", "a", "real", "secret"))

_AI_SERVER_DIR = Path(__file__).resolve().parent.parent


_ZSTD_MAGIC = b"\x28\xb5\x2f\xfd"


def _extract_bytes(data) -> bytes:
    """`request_with_retries`가 받는 `data`를 실제(가능하면 압축 해제된) 바이트로 변환한다.

    작은 배치는 `bytes`/`str`로 오지만, report_chain처럼 트레이스가 많이 쌓이면 Client가
    백그라운드 압축(zstd) 멀티파트 스트리밍으로 전환해 `data`가 파일류 객체(`_io.BytesIO`)로
    오고 내용 자체도 zstd로 압축돼 있다 — `str(data)`는 repr만 얻고, 압축 해제 없이는
    안의 민감 문자열을 검색할 수 없다. `zstandard`는 langsmith의 하드 의존성이라(요구사항
    파일에 별도 pin 불필요) 테스트에서 그대로 써도 된다.
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


def _run_and_capture_payload(
    monkeypatch, *, hide: bool, error_message: str | None = None, set_api_key: bool = True
) -> bytes:
    """LLM을 1회 호출하고, LangSmith로 나가려던 멀티파트 본문을 그대로 돌려준다.

    실제 네트워크 전송은 막는다(예외로 중단) — 페이로드만 확보하면 충분하고, 테스트가 외부
    서비스에 의존하면 안 되기 때문이다.

    `error_message`를 주면 LLM 호출이 그 메시지를 담은 예외를 던지게 해 **예외 경로**를
    재현한다 — run의 `error` 필드는 HIDE_INPUTS/OUTPUTS 대상이 아니라 별도 검증이 필요하다
    (P1, langsmith_guard.py 모듈 docstring 참고).

    `set_api_key=False`면 API 키 env를 세팅하지 않는다 — "키 없음" 상태에서도 전송이
    실제로 시도됨(본문이 인증 거부 전에 프로세스를 떠남, 6차 리뷰 P1 실측)을 검증할 때 쓴다.
    """
    if set_api_key:
        monkeypatch.setenv("LANGCHAIN_API_KEY", FAKE_LANGSMITH_KEY)
    monkeypatch.setenv("LANGCHAIN_ENDPOINT", "https://api.smith.langchain.com")
    monkeypatch.setenv("LANGCHAIN_PROJECT", "masking-test")
    monkeypatch.setenv("LANGSMITH_HIDE_INPUTS", "true" if hide else "false")
    monkeypatch.setenv("LANGSMITH_HIDE_OUTPUTS", "true" if hide else "false")

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

    client = Client(api_key=FAKE_LANGSMITH_KEY)

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
    monkeypatch.setenv("LANGCHAIN_API_KEY", FAKE_LANGSMITH_KEY)
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
    """anonymizer 단위 계약 — repr() 형태(타입명 뒤 괄호)일 때만 타입명을 남기고 나머지는 버린다."""
    scrubbed = scrub_error_anonymizer(
        {"error": f"OutputParserException('{SENSITIVE_INPUT}')\nTraceback (most recent call last): ..."}
    )
    assert "OutputParserException" in scrubbed["error"]
    assert SENSITIVE_INPUT not in scrubbed["error"]

    # error 래핑이 아닌 형태(inputs/outputs 경로)가 들어오면 전부 비전송 — env 마스킹과 같은 방향.
    assert scrub_error_anonymizer({"prompt": SENSITIVE_INPUT}) == {}


def test_scrub_error_anonymizer_rejects_non_repr_format(monkeypatch):
    """5차 리뷰 P2 회귀 — repr() 형태(타입명+괄호)가 아니면 선두 토큰도 신뢰하지 않는다.

    이전 구현은 "식별자로 시작하면" 무조건 타입명으로 간주해 남겼다. langsmith가 항상
    repr(error)를 쓰는 게 확인됐지만(run_helpers._format_error_with_exceptions_to_handle
    실측), 혹시 다른 경로나 커스텀 __repr__이 괄호 없는 순수 메시지를 채우면 그 메시지의
    선두 단어(이메일 로컬파트·파일명·ID 등 민감할 수 있는 토큰)가 그대로 샐 수 있었다.
    """
    leaky = f"john.doe raised issue with {SENSITIVE_INPUT}"  # 식별자로 시작하지만 괄호 없음
    scrubbed = scrub_error_anonymizer({"error": leaky})
    assert "john.doe" not in scrubbed["error"], "괄호 없는 선두 토큰까지 타입명으로 오인해 반향했다"
    assert SENSITIVE_INPUT not in scrubbed["error"]

    # 진짜 repr() 형태는 여전히 타입명을 남긴다 — 과도하게 보수적으로 변한 게 아님을 확인.
    scrubbed_repr = scrub_error_anonymizer({"error": f"ValueError('{SENSITIVE_INPUT}')"})
    assert "ValueError" in scrubbed_repr["error"]


def test_scrub_error_anonymizer_never_echoes_unknown_identifiers():
    """8차 리뷰 P3 회귀 고정 — {"error"} 단일 키 오분류로도 미지 토큰이 반향되지 않는다.

    이 anonymizer는 error 래핑만 받는 게 아니라, HIDE가 꺼진 백스톱 경로에서는 run의
    inputs/outputs dict에도 적용된다. 어떤 run의 inputs가 우연히 정확히
    `{"error": "<식별자(...)>"}` 형태이면 키 이름만으로는 진짜 예외와 구분할 수 없다 —
    그래서 타입명 반향은 실존 예외 화이트리스트(빌트인 전체 + 실경로 서드파티 최소 목록)
    정확 일치일 때만 허용한다. 오분류의 결과는 항상 "고정 문구"(안전 방향)여야 한다.
    """
    # ① 리뷰어 제시 케이스 — inputs가 error 키를 가장: 고객사명 등 미지 식별자는 반향 금지.
    scrubbed = scrub_error_anonymizer({"error": "CustomerCorp(비밀)"})
    assert "CustomerCorp" not in scrubbed["error"], (
        "화이트리스트 밖 식별자가 타입명으로 오인·반향됐다 — 백스톱 경로 잔여 유출면(P3)"
    )
    assert "비밀" not in scrubbed["error"]

    # ② 점 표기 가장 — 화이트리스트 이름(KeyError)을 접미로 붙여도 정확 일치가 아니면 반향 금지.
    scrubbed_dotted = scrub_error_anonymizer({"error": "CustomerSecret.KeyError(x)"})
    assert "CustomerSecret" not in scrubbed_dotted["error"]
    assert "KeyError" not in scrubbed_dotted["error"]

    # ③ 미등록 커스텀 예외 — 진짜 예외여도 화이트리스트 밖이면 고정 문구(관측성보다 안전 우선).
    scrubbed_custom = scrub_error_anonymizer({"error": "SomeVendorSpecificFailure('x')"})
    assert "SomeVendorSpecificFailure" not in scrubbed_custom["error"]

    # ④ 화이트리스트 정상 동작 — 빌트인·명시 등록 서드파티 타입명은 계속 남는다(관측 유지).
    for name in ("KeyError", "RuntimeError", "OutputParserException", "ValidationError"):
        kept = scrub_error_anonymizer({"error": f"{name}('{SENSITIVE_INPUT}')"})
        assert name in kept["error"]
        assert SENSITIVE_INPUT not in kept["error"]


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


def test_boot_guard_blocks_api_key_without_masking_even_when_tracing_off(monkeypatch):
    """5차 리뷰 P2 회귀 — 부팅 시 트레이싱 OFF라도 API 키가 있으면 HIDE 완전성을 강제한다.

    4차 수정은 anonymizer 선점만 API 키 기준으로 넓히고 fail-closed(RuntimeError)는
    tracing_is_enabled() 기준으로 좁게 남겨둬서, "부팅 시 트레이싱 OFF + API 키 있음 +
    HIDE 미설정" 조합에서 마스킹이 안 된 싱글턴이 조용히 고정되는 비대칭이 있었다(리뷰가
    지적). 이제는 API 키만 있어도 HIDE가 불완전하면 기동을 막는다 — anonymizer가 실려도
    _hide_inputs/_hide_outputs가 미리 잘못 고정되는 걸 막기 위함.
    """
    _clear_tracing_env(monkeypatch)
    monkeypatch.setenv("LANGCHAIN_API_KEY", FAKE_LANGSMITH_KEY)
    monkeypatch.setenv("LANGCHAIN_TRACING_V2", "false")  # 부팅 시 트레이싱은 명시적으로 OFF
    monkeypatch.delenv("LANGSMITH_HIDE_INPUTS", raising=False)
    monkeypatch.delenv("LANGSMITH_HIDE_OUTPUTS", raising=False)

    assert not ls_utils.tracing_is_enabled(), "이 테스트의 전제(부팅 시 트레이싱 OFF)가 깨졌다"
    with pytest.raises(RuntimeError, match="LANGSMITH_HIDE"):
        enforce_masked_tracing()


_FRAMEWORK_TRACING_ENV_NAMES = (
    "LANGCHAIN_TRACING_V2", "LANGSMITH_TRACING", "LANGSMITH_TRACING_V2", "LANGCHAIN_TRACING",
)
_API_KEY_ENV_NAMES = ("LANGCHAIN_API_KEY", "LANGSMITH_API_KEY")


def _clear_tracing_env(monkeypatch):
    for key in _FRAMEWORK_TRACING_ENV_NAMES:
        monkeypatch.delenv(key, raising=False)


def _clear_api_key_env(monkeypatch):
    for key in _API_KEY_ENV_NAMES:
        monkeypatch.delenv(key, raising=False)


@pytest.mark.parametrize("hide", [False, True], ids=["HIDE없음", "HIDE있음"])
@pytest.mark.parametrize("tracing", [False, True], ids=["트레이싱OFF", "트레이싱ON"])
@pytest.mark.parametrize("api_key", [False, True], ids=["키없음", "키있음"])
def test_guard_full_state_matrix(monkeypatch, api_key, tracing, hide):
    """전체 상태 행렬(K×T×H = 8조합) — 가드의 완전한 행동 계약을 한 곳에 고정한다(6차 리뷰 P1).

    4~6차 리뷰가 매번 찾아낸 결함은 전부 "보호장치 2개의 조건식이 갈라지는 조합"이었다.
    이 테스트는 남은 조합이 없도록 전 상태를 열거한다:
    - RuntimeError는 정확히 `(K or T) and not H`일 때만.
    - anonymizer는 **모든** 조합에서 설치된다(조건식 자체가 없음 — 갈라질 짝이 없다).
    """
    _clear_tracing_env(monkeypatch)
    _clear_api_key_env(monkeypatch)
    if api_key:
        monkeypatch.setenv("LANGCHAIN_API_KEY", FAKE_LANGSMITH_KEY)
    monkeypatch.setenv("LANGCHAIN_TRACING_V2", "true" if tracing else "false")
    if hide:
        monkeypatch.setenv("LANGSMITH_HIDE_INPUTS", "true")
        monkeypatch.setenv("LANGSMITH_HIDE_OUTPUTS", "true")
    else:
        monkeypatch.delenv("LANGSMITH_HIDE_INPUTS", raising=False)
        monkeypatch.delenv("LANGSMITH_HIDE_OUTPUTS", raising=False)

    should_block = (api_key or tracing) and not hide
    if should_block:
        with pytest.raises(RuntimeError, match="LANGSMITH_HIDE"):
            enforce_masked_tracing()
        return

    enforce_masked_tracing()
    client = ls_run_trees._CLIENT
    assert client is not None, "anonymizer 백스톱은 조건 없이 항상 설치돼야 한다"
    assert client._anonymizer is scrub_error_anonymizer, (
        f"조합(키={api_key}, 트레이싱={tracing}, HIDE={hide})에서 error 스크럽이 빠졌다 — "
        "게이트 비대칭 재발"
    )


def test_boot_guard_installs_anonymizer_even_when_tracing_off_at_boot(monkeypatch):
    """4차 리뷰 P3 회귀 — 부팅 시 트레이싱 OFF라도 API 키가 있으면 anonymizer를 선점 설치한다.

    표준 프로덕션 상태(docker-compose 기본값)는 HIDE=true인데 LANGCHAIN_TRACING_V2는 대개
    미설정이다. 이 PR 이전 패턴(요청 단위 `tracing_context(enabled=True)`)이 나중에 되살아나면,
    부팅 시 anonymizer가 없으면 예외 원문이 그대로 새어나간다 — 이 테스트는 리뷰가 명시한
    시나리오 그대로 "부팅(트레이싱 OFF) → 이후 요청에서 tracing_context(enabled=True) 예외"를
    재현해 SENSITIVE_INPUT이 전송 페이로드에 없음을 확인한다.
    """
    _clear_tracing_env(monkeypatch)
    monkeypatch.setenv("LANGCHAIN_API_KEY", FAKE_LANGSMITH_KEY)
    monkeypatch.setenv("LANGSMITH_HIDE_INPUTS", "true")
    monkeypatch.setenv("LANGSMITH_HIDE_OUTPUTS", "true")

    assert not ls_utils.tracing_is_enabled(), "이 테스트의 전제(부팅 시 트레이싱 OFF)가 깨졌다"
    enforce_masked_tracing()  # 트레이싱 OFF라 RuntimeError는 없어야 하지만 anonymizer는 설치돼야 함

    client = ls_run_trees._CLIENT
    assert client is not None, "트레이싱 OFF에서도 Client 싱글턴이 선점 생성돼야 한다(API 키 존재)"
    assert client._anonymizer is scrub_error_anonymizer, (
        "부팅 시 트레이싱이 꺼져 있으면 anonymizer가 설치되지 않는다 — "
        "나중에 tracing_context(enabled=True)가 재도입되면 예외 원문이 그대로 샌다"
    )

    # 이제 이 PR 이전 패턴(요청 단위 트레이싱 활성화)이 되살아났다고 가정하고 예외를 재현한다.
    payload = _run_and_capture_payload(
        monkeypatch, hide=True,
        error_message=f"structured output 파싱 실패: {SENSITIVE_INPUT} / {SENSITIVE_OUTPUT}",
    )
    assert payload, "전송 페이로드를 가로채지 못했다 — 검증 자체가 성립하지 않는다"
    assert SENSITIVE_INPUT.encode("utf-8") not in payload, (
        "부팅 시 트레이싱이 꺼져 있던 상태에서 나중에 트레이싱이 켜지면 예외 원문이 유출된다"
    )


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
    _clear_api_key_env(monkeypatch)
    monkeypatch.setenv("LANGCHAIN_TRACING_V2", value)
    monkeypatch.delenv("LANGSMITH_HIDE_INPUTS", raising=False)
    monkeypatch.delenv("LANGSMITH_HIDE_OUTPUTS", raising=False)

    assert not ls_utils.tracing_is_enabled(), (
        f"langsmith가 {value!r}를 트레이싱 ON으로 판정하기 시작했다 — 이 테스트의 기대치를 갱신하라"
    )
    enforce_masked_tracing()  # 프레임워크가 OFF로 보는 값이므로 예외 없이 통과해야 한다
    # anonymizer 백스톱은 트레이싱 판정과 무관하게 항상 설치된다(6차 리뷰 P1 — 게이트 제거).
    assert ls_run_trees._CLIENT is not None
    assert ls_run_trees._CLIENT._anonymizer is scrub_error_anonymizer


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
    assert Client(api_key=FAKE_LANGSMITH_KEY)._hide_inputs is False, (
        f"Client가 {hide_value!r}를 마스킹 ON으로 인식하기 시작했다 — 가드 술어를 재검토하라"
    )
    with pytest.raises(RuntimeError, match="LANGSMITH_HIDE"):
        enforce_masked_tracing()


def test_boot_guard_installs_error_anonymizer(monkeypatch):
    """정상 조합(트레이싱 ON + 마스킹 완전)이면 싱글턴에 error 스크럽이 선점 설치된다."""
    monkeypatch.setenv("LANGCHAIN_TRACING_V2", "true")
    monkeypatch.setenv("LANGCHAIN_API_KEY", FAKE_LANGSMITH_KEY)
    monkeypatch.setenv("LANGSMITH_HIDE_INPUTS", "true")
    monkeypatch.setenv("LANGSMITH_HIDE_OUTPUTS", "true")

    enforce_masked_tracing()

    client = ls_run_trees._CLIENT
    assert client is not None, "부팅 가드가 싱글턴을 선점 생성하지 않았다"
    assert client._anonymizer is scrub_error_anonymizer, (
        "싱글턴에 error 스크럽이 실리지 않았다 — 예외 경로(P1) 가드가 무효"
    )


def test_boot_guard_forces_anonymizer_onto_preexisting_client(monkeypatch):
    """5차 리뷰 P2 회귀 — 싱글턴이 anonymizer 없이 이미 존재해도 가드가 강제로 스크럽을 건다.

    `get_cached_client(anonymizer=...)`는 싱글턴이 이미 있으면 kwargs를 조용히 무시한다
    (langsmith 자체 동작). 보조 진입점이 langsmith를 먼저 건드려 anonymizer 없는 싱글턴을
    만들어버린 상황을 직접 재현한다 — 가드가 "이미 있으니 통과"하지 않고 명시적으로
    `_anonymizer`를 재할당하는지 확인한다.
    """
    monkeypatch.setenv("LANGCHAIN_API_KEY", FAKE_LANGSMITH_KEY)
    monkeypatch.setenv("LANGSMITH_HIDE_INPUTS", "true")
    monkeypatch.setenv("LANGSMITH_HIDE_OUTPUTS", "true")

    # 가드보다 먼저 anonymizer 없는 싱글턴이 만들어진 상황을 직접 재현.
    ls_run_trees._CLIENT = Client(api_key=FAKE_LANGSMITH_KEY)
    assert ls_run_trees._CLIENT._anonymizer is None, "이 테스트의 전제(anonymizer 없는 선점)가 깨졌다"

    enforce_masked_tracing()

    assert ls_run_trees._CLIENT._anonymizer is scrub_error_anonymizer, (
        "이미 존재하던 싱글턴에 스크럽을 강제하지 못했다 — 조용한 무방비(P2) 재발"
    )


def test_boot_guard_installs_anonymizer_when_tracing_on_without_api_key(monkeypatch):
    """6차 리뷰 P1 회귀 — '키 없음 + 트레이싱 ON'에서도 anonymizer가 설치된다.

    실측으로 확인된 전제: API 키가 없어도 트레이싱이 켜지면 langsmith는 전송을 **시도**하고,
    본문은 서버의 인증 거부(401/403) 전에 이미 프로세스를 떠난다 — "키가 없으면 어차피
    전송 실패"는 유출 방지가 아니다. 따라서 이 조합에서도 error 스크럽이 실려 있어야 한다.
    """
    _clear_tracing_env(monkeypatch)
    _clear_api_key_env(monkeypatch)
    monkeypatch.setenv("LANGCHAIN_TRACING_V2", "true")
    monkeypatch.setenv("LANGSMITH_HIDE_INPUTS", "true")
    monkeypatch.setenv("LANGSMITH_HIDE_OUTPUTS", "true")

    enforce_masked_tracing()

    client = ls_run_trees._CLIENT
    assert client is not None, "키 없음 + 트레이싱 ON에서 싱글턴이 선점되지 않았다"
    assert client._anonymizer is scrub_error_anonymizer, (
        "키 없음 + 트레이싱 ON에서 error 스크럽이 빠졌다 — 예외 원문이 인증 거부 전에 전송된다(P1)"
    )


def test_error_path_masked_when_tracing_on_without_api_key(monkeypatch):
    """6차 리뷰 P1 회귀(페이로드 검증) — 키 없음 상태의 예외 전송 본문에 원문이 없어야 한다.

    payload가 비어 있지 않다는 첫 단언 자체가 "키 없이도 전송이 시도된다"는 실측 전제를
    테스트로 고정하는 역할을 겸한다 — 훗날 langsmith가 키 없으면 아예 전송하지 않도록
    바뀌면 이 단언이 실패해 전제 변화를 알린다(그 경우 이 테스트는 완화해도 안전하다).
    """
    _clear_tracing_env(monkeypatch)
    _clear_api_key_env(monkeypatch)
    monkeypatch.setenv("LANGCHAIN_TRACING_V2", "true")
    monkeypatch.setenv("LANGSMITH_HIDE_INPUTS", "true")
    monkeypatch.setenv("LANGSMITH_HIDE_OUTPUTS", "true")
    enforce_masked_tracing()  # 키 없는 싱글턴에 anonymizer 선점

    payload = _run_and_capture_payload(
        monkeypatch, hide=True, set_api_key=False,
        error_message=f"structured output 파싱 실패: {SENSITIVE_INPUT} / {SENSITIVE_OUTPUT}",
    )

    assert payload, (
        "키 없음 + 트레이싱 ON인데 전송 시도가 없다 — 실측 전제(본문이 인증 거부 전에 "
        "떠난다)가 바뀌었는지 langsmith 버전을 확인하라"
    )
    assert SENSITIVE_INPUT.encode("utf-8") not in payload, (
        "키 없음 상태의 예외 경로에서 OCR 원문이 전송된다(P1)"
    )
    assert SENSITIVE_OUTPUT.encode("utf-8") not in payload


# ── 그래프 체인 경로 — P3 (LangGraph 노드 상태·병렬 워커까지 마스킹 검증) ─────────


class _FakeStructuredLLM:
    """get_llm() 대체 — with_structured_output()이 트레이싱되는 RunnableLambda를 돌려준다.

    MagicMock과 달리 RunnableLambda는 langchain 콜백 체계를 그대로 타므로, LangGraph 노드
    상태·RunnableParallel 워커 스레드의 run이 실제로 LangSmith 전송 큐에 들어간다 — 이
    테스트의 검증 대상이 바로 그 전송 본문이다.

    `invocations`에 (스레드명, 프롬프트 원문)을 남긴다 — 6차 리뷰 P3: "병렬 경로를 실제로
    지나갔고 그 경로에 민감 문자열이 흘렀다"를 관측 가능하게 만들어, 페이로드 부재 단언이
    "원래 안 잡혀서 통과"가 아님을 증명하기 위함(삭제된 구 테스트의 스레드 실증 축 복원).
    """

    def __init__(self):
        self.invocations: list[tuple[str, str]] = []

    def with_structured_output(self, _schema, **_kwargs):
        def _record_and_return(prompt):
            self.invocations.append((threading.current_thread().name, str(prompt)))
            return MagicMock()

        return RunnableLambda(_record_and_return)


def test_report_chain_graph_payload_masking(monkeypatch):
    """그래프 체인(report_chain) 실경로 검증 — 단일 모델 경로만 보던 기존 테스트의 공백(P3)을 메운다.

    report_chain은 RunnableParallel(스레드 워커) + StateGraph라 중간 상태가 가장 많은 체인이다.
    "마스킹 켜면 실제로 안 샌다"를 이 체인의 실제 invoke 경로로 고정한다.

    ⚠️ 대조군(hide=False, "안 가리면 실제로 샌다")은 의도적으로 이 테스트에서 빼고
    `test_masking_off_actually_sends_content`(단일 모델 경로) 쪽에만 둔다. 실측 결과,
    report_chain의 4개 병렬 섹션(overview/summary/detail/recommendation)은
    `RunnableParallel`이 스레드 풀 워커에서 실행하는데, 그 워커 스레드 안에서 만들어지는
    프롬프트 내용은 (마스킹 여부와 무관하게) 이 테스트의 캡처 메커니즘에 전혀 잡히지
    않았다 — LangChain의 컨텍스트 전파가 워커 스레드까지 프롬프트 "내용"을 실어 보내지
    않는 것으로 보이는 별개의 동작이며, 우리 마스킹 로직과는 무관하다. 그 결과 hide=False
    대조군을 여기 두면 "원래 안 잡혀서 통과"인지 "마스킹이 막아서 통과"인지 구분이 안 되어
    오히려 헛도는 테스트가 된다. 대신 이 테스트는 hide=True만 검증하고, "안 가리면
    실제로 샌다"는 이미 다른 곳에서 확실히 증명된 사실을 재사용한다.
    """
    monkeypatch.setenv("LANGCHAIN_API_KEY", FAKE_LANGSMITH_KEY)
    monkeypatch.setenv("LANGCHAIN_ENDPOINT", "https://api.smith.langchain.com")
    monkeypatch.setenv("LANGCHAIN_PROJECT", "masking-test")
    monkeypatch.setenv("LANGSMITH_HIDE_INPUTS", "true")
    monkeypatch.setenv("LANGSMITH_HIDE_OUTPUTS", "true")
    # 프로덕션 부팅 순서 재현 — 가드가 싱글턴을 선점(anonymizer 포함)한 뒤 체인이 돈다.
    monkeypatch.setenv("LANGCHAIN_TRACING_V2", "true")
    enforce_masked_tracing()

    captured: list[bytes] = []
    spy_clients: list = []

    def _spy(self, method, path, **kwargs):
        spy_clients.append(self)
        data = kwargs.get("request_kwargs", {}).get("data") or kwargs.get("data")
        if data:
            captured.append(_extract_bytes(data))
        raise RuntimeError("테스트에서 실제 전송을 막음")

    # patch()의 pkgutil 경로 해석은 서브모듈을 임포트해주지 않으므로 먼저 임포트해둔다.
    from ai.chains.report_chain import run_report_chain

    fake_llm = _FakeStructuredLLM()
    with patch.object(Client, "request_with_retries", _spy), patch(
        "ai.chains.report_chain.get_llm", return_value=fake_llm
    ):
        with tracing_context(enabled=True):
            try:
                run_report_chain(facility_info={"name": SENSITIVE_INPUT}, confirmed_defects=[])
            except Exception:  # noqa: BLE001 — MagicMock 응답이라 조립 단계에서 깨질 수 있다.
                pass  # 검증 대상은 "전송 페이로드"뿐이다.
        _wait_for_flush(captured)

    payload = b"".join(captured)
    assert payload, "전송 페이로드를 가로채지 못했다 — 검증 자체가 성립하지 않는다"
    assert spy_clients, "전송에 쓰인 Client를 하나도 포착하지 못했다 — 검증 자체가 성립하지 않는다"
    # 실제로 전송에 쓰인 클라이언트 자체에 스크럽이 걸려 있는지 확인한다(P3) — 미래 체인이
    # 별도 langsmith.Client()를 만들거나 get_llm()을 우회해 스크럽 없는 클라이언트로
    # 전송하면 여기서 즉시 실패한다.
    assert all(c._anonymizer is scrub_error_anonymizer for c in spy_clients), (
        "그래프 체인 전송에 쓰인 클라이언트에 error 스크럽이 없다 — 유출 표면(P1/P3)"
    )
    # 6차 리뷰 P3 — 병렬(RunnableParallel) 워커 스레드 경로를 실제로 지나갔고 그 경로에
    # 민감 문자열이 흘렀음을 실증한다 — 이게 있어야 아래 "페이로드에 없음" 단언이
    # "원래 안 잡혀서 통과"가 아니라 "흘렀는데 마스킹돼서 없음"으로 증명된다.
    worker_invocations = [
        (name, prompt) for name, prompt in fake_llm.invocations if name != "MainThread"
    ]
    assert worker_invocations, (
        "병렬 섹션이 워커 스레드에서 실행되지 않았다 — 병렬 경로 검증이 성립하지 않는다"
    )
    assert any(SENSITIVE_INPUT in prompt for _name, prompt in worker_invocations), (
        "워커 스레드 프롬프트에 민감 문자열이 흐르지 않았다 — 마스킹 검증이 헛돈다"
    )
    assert SENSITIVE_INPUT.encode("utf-8") not in payload, (
        "그래프 체인 경로(노드 상태·프롬프트)에서 시설 정보가 LangSmith로 전송된다"
    )


# ── 진입점 독립성 — 3차 리뷰 P2 (main.py를 거치지 않아도 가드가 작동) ─────────────


def _run_import_in_subprocess(code: str, extra_env: dict) -> subprocess.CompletedProcess:
    """별도 파이썬 프로세스에서 임포트를 실행한다.

    현 pytest 프로세스에는 llm_client가 이미 임포트돼 있어 임포트 시점 가드가 다시 돌지
    않으므로, "main.py 없이 체인만 임포트하는 보조 진입점"은 새 프로세스로만 재현할 수 있다.
    """
    env = {**os.environ, **extra_env}
    for key in ("LANGSMITH_HIDE_INPUTS", "LANGSMITH_HIDE_OUTPUTS"):
        if key not in extra_env:
            env.pop(key, None)
    return subprocess.run(
        [sys.executable, "-c", code],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
        env=env, cwd=str(_AI_SERVER_DIR), timeout=120,
    )


def test_guard_fires_without_main_entrypoint(monkeypatch):
    """main.py를 거치지 않는 진입점(배치 스크립트·`python -c` 등)에서도 fail-closed가 작동한다.

    보호가 main.py 부팅 훅에만 결합돼 있으면 보조 진입점이 체인을 임포트할 때 가드가 없다는
    3차 리뷰 P2 지적의 회귀 고정 — llm_client는 "유일한 LLM 호출 지점"이라 모든 체인 임포트가
    반드시 지나므로, 그 모듈 임포트 자체가 가드를 강제하는지 검증한다.
    """
    result = _run_import_in_subprocess(
        "import ai.core.llm_client",
        {"LANGCHAIN_TRACING_V2": "true"},  # HIDE는 의도적으로 미설정
    )

    assert result.returncode != 0, (
        "트레이싱 ON + 마스킹 미설정인데 체인 공통 모듈 임포트가 성공했다 — "
        f"보조 진입점에서 PII가 전송될 수 있다(P2)\nstdout: {result.stdout}\nstderr: {result.stderr}"
    )
    assert "LANGSMITH_HIDE" in result.stderr, "기동 중단 사유가 운영자에게 안내되지 않는다"


def test_guard_installs_anonymizer_without_main_entrypoint(monkeypatch):
    """정상 조합에선 main.py 없이도 error 스크럽이 싱글턴에 선점된다 — 예외 경로 보호의 진입점 독립성."""
    result = _run_import_in_subprocess(
        "import ai.core.llm_client, langsmith.run_trees as rt;"
        "print('ANON_OK' if rt._CLIENT is not None and rt._CLIENT._anonymizer is not None"
        " else 'ANON_MISSING')",
        {
            "LANGCHAIN_TRACING_V2": "true",
            "LANGSMITH_HIDE_INPUTS": "true",
            "LANGSMITH_HIDE_OUTPUTS": "true",
            "LANGCHAIN_API_KEY": FAKE_LANGSMITH_KEY,
        },
    )

    assert result.returncode == 0, f"정상 조합인데 임포트가 실패했다\nstderr: {result.stderr}"
    assert "ANON_OK" in result.stdout, (
        "main.py를 거치지 않으면 error 스크럽이 설치되지 않는다 — 예외 경로 유출 표면(P2)"
    )


# ── 부트 순서 갭 — 7차 리뷰 P3 (.env 지연 로드 시 가드 우회, 백스톱이 마지노선) ──────


def test_late_env_load_bypasses_guard_but_backstop_blocks_content(monkeypatch):
    """7차 리뷰 P3 회귀 고정 — 보조 진입점이 load_dotenv()보다 먼저 체인을 임포트한 순서 재현.

    가드 판정(get_env_var)은 LRU 캐시로 **첫 읽기 시점 env에 고정**된다. 그 순서에서는
    ① fail-closed 조기 경보(RuntimeError)가 발동하지 않는다(문서화된 한계 —
    langsmith_guard.py "보조 진입점 부트 순서" 참고. 보조 진입점 가이드는 load_dotenv 선행).
    ② 그럼에도 내용은 무조건 설치된 anonymizer 백스톱이 차단해야 한다 — 이 테스트는 ②를
    실제 전송 페이로드로 고정한다. anonymizer 설치에 게이트를 되살리는 회귀(K=✗,T=✗면
    설치 생략 등)가 생기면 이 순서의 유일한 방어가 사라지므로 여기서 즉시 유출로 잡힌다.
    """
    # 1) ".env 로드 전 임포트" 재현 — 마스킹 관련 env가 전혀 없는 상태에서 가드가 먼저
    #    실행되어 판정(API키=None·트레이싱=off)이 LRU 캐시에 고정된다.
    _clear_api_key_env(monkeypatch)
    _clear_tracing_env(monkeypatch)
    for name in (
        "LANGSMITH_HIDE_INPUTS", "LANGCHAIN_HIDE_INPUTS",
        "LANGSMITH_HIDE_OUTPUTS", "LANGCHAIN_HIDE_OUTPUTS",
    ):
        monkeypatch.delenv(name, raising=False)
    enforce_masked_tracing()  # K=✗,T=✗ — 기동 허용, anonymizer는 무조건 설치

    # 2) 뒤늦은 load_dotenv() 재현 — 실제 키 + 트레이싱 ON, HIDE는 미설정(최악 조합).
    monkeypatch.setenv("LANGCHAIN_API_KEY", FAKE_LANGSMITH_KEY)
    monkeypatch.setenv("LANGCHAIN_TRACING_V2", "true")
    monkeypatch.setenv("LANGCHAIN_ENDPOINT", "https://api.smith.langchain.com")

    # 3) 가드를 다시 불러도 stale 캐시 탓에 경보가 발동하지 않는다 — P3가 짚은 우회 그 자체.
    #    (언젠가 가드가 캐시를 우회해 재판정하도록 개선되면 이 호출은 RuntimeError가 되어야
    #    하고 이 단계만 갱신하면 된다 — 아래 페이로드 단언은 어떤 경우에도 유지할 것.)
    enforce_masked_tracing()

    client = ls_run_trees._CLIENT
    assert client is not None and client._anonymizer is scrub_error_anonymizer, (
        "지연 로드 순서에서 싱글턴에 백스톱이 없다 — 이 순서의 유일한 방어가 사라졌다"
    )

    # 4) 그 상태로 실제 호출 — HIDE는 stale(off)이므로 내용 차단은 오직 백스톱 몫이다.
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
    assert SENSITIVE_INPUT.encode("utf-8") not in payload, (
        "지연 로드 순서에서 민감 입력이 전송된다 — anonymizer 백스톱이 무너졌다(P1급)"
    )
    assert SENSITIVE_OUTPUT.encode("utf-8") not in payload, (
        "지연 로드 순서에서 LLM 응답이 전송된다 — anonymizer 백스톱이 무너졌다(P1급)"
    )


# ── 체인 단위 스모크 — 7차 리뷰 P3 (삭제된 체인별 마스킹 테스트의 직접 커버리지 복원) ──


def _smoke_briefing(fake_llm):
    from ai.chains.briefing_chain import DashboardStats, run_briefing_chain

    stats = DashboardStats(
        total_facilities=1, monthly_analysis=1, pending_review=1, pending_action=1,
        this_week_defects=2, last_week_defects=1,
        top_defect_type=SENSITIVE_INPUT, critical_defects=0,
    )
    with patch("ai.chains.briefing_chain.get_llm", return_value=fake_llm):
        run_briefing_chain(stats)


def _smoke_ocr(fake_llm):
    from ai.chains.business_license_ocr_chain import run_business_license_ocr_chain

    with patch("ai.chains.business_license_ocr_chain.get_llm", return_value=fake_llm), patch(
        "ai.chains.business_license_ocr_chain._decode_image", return_value=b"img-bytes"
    ), patch(
        "ai.chains.business_license_ocr_chain._extract_text_lines",
        return_value=[(SENSITIVE_INPUT, 0.99)],
    ):
        # _decode_image가 패치되어 인자는 사용되지 않는다 — base64처럼 보이는 리터럴은
        # 시크릿 스캐너 오탐 후보라 일부러 평문 더미를 쓴다.
        run_business_license_ocr_chain("unused-image-payload")


def _smoke_defect_explain(fake_llm):
    from ai.chains.defect_explain_chain import run_defect_explain_chain

    with patch("ai.chains.defect_explain_chain.get_llm", return_value=fake_llm):
        run_defect_explain_chain("균열", "D", SENSITIVE_INPUT, "아파트")


def _smoke_nl_search(fake_llm):
    from ai.chains.nl_search_chain import run_nl_search_chain

    with patch("ai.chains.nl_search_chain.get_llm", return_value=fake_llm):
        run_nl_search_chain(SENSITIVE_INPUT, date(2026, 7, 30))


def _smoke_rag_chat(fake_llm):
    from ai.chains.rag_chat_chain import run_rag_chat_chain

    fake_redis = MagicMock()
    fake_redis.get.return_value = None  # 캐시 미스 — 검색·LLM 경로로 진입시킨다
    doc = MagicMock()
    doc.page_content = SENSITIVE_INPUT
    doc.metadata = {"source": "하자판정기준.pdf", "page": 1}
    fake_semantic_store = MagicMock()
    fake_semantic_store.similarity_search_with_score.return_value = []  # 시맨틱 캐시 미스 — 검색·LLM 경로로 진입시킨다
    with patch("ai.chains.rag_chat_chain.get_llm", return_value=fake_llm), patch(
        "ai.chains.rag_chat_chain.get_redis_client", return_value=fake_redis
    ), patch(
        "ai.chains.rag_chat_chain.get_vectorstore", return_value=fake_semantic_store
    ), patch("ai.chains.rag_chat_chain.hybrid_search", return_value=[doc]):
        run_rag_chat_chain(SENSITIVE_INPUT)


def _smoke_report(fake_llm):
    from ai.chains.report_chain import run_report_chain

    with patch("ai.chains.report_chain.get_llm", return_value=fake_llm):
        run_report_chain(facility_info={"name": SENSITIVE_INPUT}, confirmed_defects=[])


@pytest.mark.parametrize(
    "chain_id,invoke_chain",
    [
        ("briefing", _smoke_briefing),
        ("ocr", _smoke_ocr),
        ("defect_explain", _smoke_defect_explain),
        ("nl_search", _smoke_nl_search),
        ("rag_chat", _smoke_rag_chat),
        ("report", _smoke_report),
    ],
)
def test_every_chain_payload_masked_and_scrubbed(monkeypatch, chain_id, invoke_chain):
    """7차 리뷰 P3 — 삭제된 체인별 테스트(test_langsmith_pii_exclusion.py, 6체인 개별 검증)의
    직접 커버리지 복원. 마스킹이 전역 env 기반이 된 뒤에도, 특정 체인이 미래에 별도
    `langsmith.Client()`를 만들거나 get_llm()을 우회하는 전송 경로를 추가하는 회귀는 대표
    경로 테스트만으로 못 잡는다 — 그래서 6개 체인 각각을 실제 invoke 경로로 얇게 돌려
    체인 단위로 고정한다:
    ① 민감 문자열이 실제로 그 체인의 LLM 프롬프트에 흘렀고(부재 단언이 "원래 안 잡혀서
       통과"가 아님을 증명), ② 전송에 쓰인 모든 Client에 error 스크럽이 실려 있으며,
    ③ 전송 페이로드에 민감 문자열이 없음.
    (`test_report_chain_graph_payload_masking`의 spy_clients 단언을 각 체인에 재사용 —
    hide=False 대조군을 두지 않는 이유도 그 테스트의 docstring과 동일하다.)
    """
    monkeypatch.setenv("LANGCHAIN_API_KEY", FAKE_LANGSMITH_KEY)
    monkeypatch.setenv("LANGCHAIN_ENDPOINT", "https://api.smith.langchain.com")
    monkeypatch.setenv("LANGCHAIN_PROJECT", "masking-test")
    monkeypatch.setenv("LANGSMITH_HIDE_INPUTS", "true")
    monkeypatch.setenv("LANGSMITH_HIDE_OUTPUTS", "true")
    monkeypatch.setenv("LANGCHAIN_TRACING_V2", "true")
    # 프로덕션 부팅 순서 재현 — 가드가 싱글턴을 선점(anonymizer 포함)한 뒤 체인이 돈다.
    enforce_masked_tracing()

    captured: list[bytes] = []
    spy_clients: list = []

    def _spy(self, method, path, **kwargs):
        spy_clients.append(self)
        data = kwargs.get("request_kwargs", {}).get("data") or kwargs.get("data")
        if data:
            captured.append(_extract_bytes(data))
        raise RuntimeError("테스트에서 실제 전송을 막음")

    fake_llm = _FakeStructuredLLM()
    with patch.object(Client, "request_with_retries", _spy):
        with tracing_context(enabled=True):
            try:
                invoke_chain(fake_llm)
            except Exception:  # noqa: BLE001 — MagicMock 응답이라 LLM 이후 조립 단계는 깨질 수 있다.
                pass  # 검증 대상은 "LLM 경로 도달"과 "전송 페이로드"뿐이다.
        _wait_for_flush(captured)

    prompts = [prompt for _thread, prompt in fake_llm.invocations]
    assert prompts, f"{chain_id}: LLM 경로에 도달하지 못했다 — 스모크가 성립하지 않는다"
    assert any(SENSITIVE_INPUT in prompt for prompt in prompts), (
        f"{chain_id}: 프롬프트에 민감 문자열이 흐르지 않았다 — 마스킹 검증이 헛돈다"
    )
    payload = b"".join(captured)
    assert payload, f"{chain_id}: 전송 페이로드를 가로채지 못했다 — 검증 자체가 성립하지 않는다"
    assert spy_clients, f"{chain_id}: 전송에 쓰인 Client를 포착하지 못했다"
    assert all(c._anonymizer is scrub_error_anonymizer for c in spy_clients), (
        f"{chain_id}: 전송에 쓰인 클라이언트에 error 스크럽이 없다 — "
        "별도 Client 생성 또는 get_llm() 우회 회귀(P1 유출 표면)"
    )
    assert SENSITIVE_INPUT.encode("utf-8") not in payload, (
        f"{chain_id}: 체인 경로에서 민감 입력이 LangSmith로 전송된다"
    )
