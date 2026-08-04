"""LangSmith 정규식 PII 스크럽 anonymizer 검증 (#1585).

#1240/#1248이 도입했던 `LANGSMITH_HIDE_INPUTS`/`LANGSMITH_HIDE_OUTPUTS` 전면 마스킹은
#1534/PR #1545에서 의도적으로 폐기됐다 — 이 테스트는 그 폐기를 되살리지 않는다는 것을
전제로, "PII 패턴만 부분 치환되고 나머지 원문은 그대로 남는다"를 검증한다.

두 계층으로 나눠 검증한다:
1. **단위 테스트** — `pii_scrub_anonymizer` 순수 함수 계약(구조 보존·부분 치환).
2. **통합 테스트** — 기존 `test_langsmith_tracing_exclusion.py`와 동일한 방식으로 실제
   전송 페이로드 바이트를 캡처(`_extract_bytes`/`_wait_for_flush` 재사용)해 마스킹이
   아니라 "부분 치환"임을 실증한다. 특히 "같은 페이로드에 일반 문장은 원문 그대로 남는다"
   케이스가 이 작업의 핵심 단언이다 — 이게 없으면 전면 마스킹과 결과로 구분이 안 된다.
"""
import io
import time
from unittest.mock import MagicMock, patch

import pytest
import zstandard
from langsmith import run_trees as ls_run_trees
from langsmith import utils as ls_utils
from langsmith.client import Client
from langsmith.run_helpers import tracing_context

from ai.core.langsmith_pii_scrub import install_pii_scrub_anonymizer, pii_scrub_anonymizer

RRN_PII = "901231-1234567"
BIZ_REG_PII = "123-45-67890"
PHONE_PII = "010-1234-5678"
EMAIL_PII = "hong@example.com"
# 규정과 무관한 일반 문장 — PII 정규식 어디에도 걸리지 않는다(가시성 회귀 방지 대조군).
GENERAL_SENTENCE = "○○아파트 3층 복도 콘크리트 균열 발생, 하자 ID 12345, 폭 2.3mm 추정"

SENSITIVE_PROMPT = f"사업자등록번호 {BIZ_REG_PII} 담당자 연락처 {PHONE_PII}. {GENERAL_SENTENCE}"
SENSITIVE_OUTPUT = f"현장 확인 결과 {GENERAL_SENTENCE} 담당자 이메일은 {EMAIL_PII} 입니다"

FAKE_LANGSMITH_KEY = "-".join(("test", "dummy", "langsmith", "key", "not", "a", "real", "secret"))
FAKE_HF_TOKEN = "-".join(("test", "dummy", "hf", "token", "not", "a", "real", "secret"))


_ZSTD_MAGIC = b"\x28\xb5\x2f\xfd"


def _extract_bytes(data) -> bytes:
    """LangSmith 전송 페이로드를 실제(압축 해제된) 바이트로 변환.

    (`tests/test_langsmith_tracing_exclusion.py`의 동일 헬퍼와 같은 방식 — 소규모 배치는
    bytes로 오지만 대규모 트레이스는 zstd로 압축돼 `_io.BytesIO`로 온다.)
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
    """백그라운드 전송이 끝날 때까지 대기(길이가 settle 초 동안 변하지 않으면 완료로 본다)."""
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
    """각 테스트마다 LangSmith 싱글턴 및 LRU 캐시를 초기화(다른 테스트 파일과 동일 이유)."""
    def _reset():
        ls_utils.get_env_var.cache_clear()
        ls_run_trees._CLIENT = None

    _reset()
    yield
    _reset()


# ── 단위 테스트 — pii_scrub_anonymizer 순수 함수 계약 ──────────────────────────


def test_scrubs_known_pii_patterns():
    data = {
        "rrn": f"주민번호 {RRN_PII} 확인",
        "biz": f"사업자등록번호 {BIZ_REG_PII}",
        "phone": f"연락처 {PHONE_PII}",
        "email": f"이메일 {EMAIL_PII}",
        "card": "카드번호 1234-5678-9012-3456",
    }
    scrubbed = pii_scrub_anonymizer(data)

    assert RRN_PII not in scrubbed["rrn"] and "[REDACTED:주민등록번호]" in scrubbed["rrn"]
    assert BIZ_REG_PII not in scrubbed["biz"] and "[REDACTED:사업자등록번호]" in scrubbed["biz"]
    assert PHONE_PII not in scrubbed["phone"] and "[REDACTED:전화번호]" in scrubbed["phone"]
    assert EMAIL_PII not in scrubbed["email"] and "[REDACTED:이메일]" in scrubbed["email"]
    assert "1234-5678-9012-3456" not in scrubbed["card"] and "[REDACTED:카드번호]" in scrubbed["card"]


def test_preserves_general_text_untouched():
    """PII가 아닌 일반 문장·숫자는 정규식에 안 걸려 원문 그대로 남아야 한다(오탐 방지)."""
    data = {"note": GENERAL_SENTENCE}
    scrubbed = pii_scrub_anonymizer(data)
    assert scrubbed["note"] == GENERAL_SENTENCE, "일반 문장이 변형됐다 — 오탐(과잉 스크럽)"


def test_preserves_dict_keys_and_nested_structure():
    """구조(dict 키·list 순서·비-str 타입)는 항상 보존된다 — 전면 마스킹(`{}` 반환)과의 차이."""
    data = {
        "prompt": f"{BIZ_REG_PII} 있는 프롬프트",
        "metadata": {"token_count": 42, "ok": True, "tags": [PHONE_PII, GENERAL_SENTENCE]},
        "empty": None,
    }
    scrubbed = pii_scrub_anonymizer(data)

    assert set(scrubbed.keys()) == set(data.keys()), "최상위 키가 사라지거나 늘어났다"
    assert set(scrubbed["metadata"].keys()) == set(data["metadata"].keys())
    assert scrubbed["metadata"]["token_count"] == 42, "비-str 값(int)이 변형됐다"
    assert scrubbed["metadata"]["ok"] is True, "비-str 값(bool)이 변형됐다"
    assert isinstance(scrubbed["metadata"]["tags"], list) and len(scrubbed["metadata"]["tags"]) == 2
    assert PHONE_PII not in scrubbed["metadata"]["tags"][0]
    assert scrubbed["metadata"]["tags"][1] == GENERAL_SENTENCE, "list 안의 일반 문장까지 지워지면 안 됨"
    assert scrubbed["empty"] is None, "None 값이 변형됐다"


def test_error_wrapped_dict_is_scrubbed_same_way():
    """`Client._hide_run_error`가 넘기는 `{"error": ...}` 형태도 동일하게 부분 치환된다.

    예외 타입명(RuntimeError 등)은 PII 패턴이 아니므로 자연히 남는다 — 스택·타입은 디버깅에
    필요하므로 보존해야 한다(handoff "예외 타입·스택은 남겨야 디버깅이 된다").
    """
    scrubbed = pii_scrub_anonymizer(
        {"error": f"RuntimeError: 파싱 실패 - {RRN_PII} / {GENERAL_SENTENCE}"}
    )
    assert RRN_PII not in scrubbed["error"]
    assert "RuntimeError" in scrubbed["error"]
    assert GENERAL_SENTENCE in scrubbed["error"]


def test_non_dict_input_returned_unchanged():
    """langsmith 계약상 항상 dict가 들어오지만, 벗어난 호출에도 예외 없이 원본을 반환한다."""
    assert pii_scrub_anonymizer("not a dict") == "not a dict"  # type: ignore[arg-type]


# ── 통합 테스트 — 실제 전송 페이로드 바이트 캡처 ──────────────────────────────


def _run_llm_and_capture_payload(monkeypatch, *, error_message: str | None = None) -> bytes:
    """`install_pii_scrub_anonymizer()`로 싱글턴을 선점한 뒤 LLM을 1회 호출하고, LangSmith로
    나가려던 멀티파트 본문을 그대로 돌려준다. 실제 네트워크 전송은 막는다.
    """
    monkeypatch.setenv("LANGCHAIN_API_KEY", FAKE_LANGSMITH_KEY)
    monkeypatch.setenv("LANGCHAIN_ENDPOINT", "https://api.smith.langchain.com")
    monkeypatch.setenv("LANGCHAIN_PROJECT", "pii-scrub-test")
    # HIDE_* env는 의도적으로 설정하지 않는다 — 이 기능은 HIDE 메커니즘과 무관하게(전면
    # 마스킹 없이) anonymizer만으로 부분 치환한다는 것 자체가 검증 대상이다.

    install_pii_scrub_anonymizer()

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
                result = model.invoke(SENSITIVE_PROMPT)
                assert result.content == SENSITIVE_OUTPUT, "스크럽이 LLM 응답 자체를 바꾸면 안 된다"
            else:
                with pytest.raises(Exception):
                    model.invoke(SENSITIVE_PROMPT)

        _wait_for_flush(captured)

    return b"".join(captured)


def test_pii_removed_but_general_sentence_survives_in_same_payload(monkeypatch):
    """이 작업의 핵심 단언 — 같은 페이로드 안에서 PII는 사라지고 일반 문장은 원문 그대로 남는다.

    이게 없으면 전면 마스킹(#1240/#1248, 이미 폐기됨)과 결과로 구분이 안 된다.
    """
    payload = _run_llm_and_capture_payload(monkeypatch)

    assert payload, "전송 페이로드를 가로채지 못했다 — 검증 자체가 성립하지 않는다"

    # PII 부분만 사라진다.
    assert BIZ_REG_PII.encode("utf-8") not in payload, "사업자등록번호가 그대로 전송됐다(P1)"
    assert PHONE_PII.encode("utf-8") not in payload, "전화번호가 그대로 전송됐다(P1)"
    assert EMAIL_PII.encode("utf-8") not in payload, "이메일이 그대로 전송됐다(P1)"

    # 같은 페이로드에서 일반 문장(가시성)은 원문 그대로 남아야 한다.
    assert GENERAL_SENTENCE.encode("utf-8") in payload, (
        "PII와 무관한 일반 문장까지 사라졌다 — 전면 마스킹으로 회귀했다(가시성 상실, "
        "#1534/PR #1545에서 폐기된 방향)"
    )

    # 부분 치환 토큰 자체는 남아 있어야 한다(=필드가 통째로 `{}`가 된 게 아님을 증명).
    assert b"[REDACTED:" in payload, "치환 토큰이 없다 — anonymizer가 아예 호출 안 됐을 가능성"


def test_error_path_scrubs_pii_but_keeps_exception_type(monkeypatch):
    """예외 경로 — error 필드 전송 페이로드에도 PII가 없어야 한다(#1240 P1과 동일 유출 경로).

    단, 전문 삭제가 아니라 부분 치환이므로 예외 타입(RuntimeError)은 디버깅을 위해 남는다.
    """
    payload = _run_llm_and_capture_payload(
        monkeypatch,
        error_message=f"structured output 파싱 실패: {RRN_PII} / {GENERAL_SENTENCE}",
    )

    assert payload, "전송 페이로드를 가로채지 못했다 — 검증 자체가 성립하지 않는다"
    assert RRN_PII.encode("utf-8") not in payload, (
        "예외 메시지의 주민등록번호가 error 필드로 LangSmith에 전송된다(개인정보 유출, P1)"
    )
    assert b"RuntimeError" in payload, (
        "예외 타입까지 사라지면 실패 추적(트레이싱 도입 목적)이 불가능하다 — 스크럽이 과하다"
    )
