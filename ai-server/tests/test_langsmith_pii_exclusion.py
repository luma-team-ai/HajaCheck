"""개인정보/고객사 정보가 LangSmith로 전송되지 않는지 검증.

business_license_ocr_chain(사업자등록번호·대표자명)과 report_chain(시설명·위치·하자내용)은
프롬프트에 민감 정보를 그대로 담으므로 `tracing_context(enabled=False)`로 감싸 트레이싱에서
제외한다. 이 테스트는 전역 트레이싱이 켜진 상태를 가정하고, LLM 호출이 실제로 일어나는 시점에
트레이싱이 꺼져 있는지를 확인한다 — 감싸는 코드를 나중에 지우면 여기서 실패한다.

conftest.py가 테스트 프로세스의 LANGCHAIN_TRACING_V2를 false로 강제하므로, 각 테스트는
tracing_context(enabled=True)로 "켜진 상태"를 명시적으로 재현한 뒤 검증한다.
"""
from unittest.mock import MagicMock, patch

from langsmith.run_helpers import get_tracing_context, tracing_context

from ai.chains.business_license_ocr_chain import (
    BusinessLicenseOcrExtract,
    run_business_license_ocr_chain,
)
from ai.chains.report_chain import run_report_chain


def _tracing_recording_llm(recorder: list, response=None) -> MagicMock:
    """invoke() 시점의 트레이싱 활성 여부를 recorder에 남기는 가짜 LLM."""

    def _record_and_return(_prompt):
        recorder.append(get_tracing_context()["enabled"])
        return response if response is not None else MagicMock()

    structured = MagicMock()
    structured.invoke.side_effect = _record_and_return

    llm = MagicMock()
    llm.with_structured_output.return_value = structured
    return llm


def test_tracing_context_is_enabled_by_default_in_this_test_setup():
    """대조군 — 감싸지 않은 호출은 트레이싱이 켜진 상태로 보인다.

    아래 두 테스트의 False가 "원래 항상 False라서" 나온 게 아님을 보장한다.
    """
    with tracing_context(enabled=True):
        assert get_tracing_context()["enabled"] is True


@patch("ai.chains.business_license_ocr_chain.get_llm")
@patch("ai.chains.business_license_ocr_chain._extract_text_lines")
def test_ocr_chain_disables_tracing_during_llm_call(mock_extract, mock_get_llm):
    """사업자등록증 OCR 원문(개인정보)이 LangSmith로 전송되지 않아야 한다."""
    mock_extract.return_value = [("사업자등록번호 123-45-67890", 0.9), ("대표자 홍길동", 0.9)]
    tracing_states: list = []
    # 후처리(정규화)가 문자열을 기대하므로 실제 스키마 객체를 응답으로 준다.
    mock_get_llm.return_value = _tracing_recording_llm(
        tracing_states,
        response=BusinessLicenseOcrExtract(
            business_registration_number="123-45-67890",
            company_name="테스트상사",
            representative_name="홍길동",
            business_start_date="2020.01.15",
        ),
    )

    # 전역 트레이싱이 켜진 상태를 재현 — 그래도 체인 내부에서는 꺼져 있어야 한다.
    with tracing_context(enabled=True):
        run_business_license_ocr_chain("aGVsbG8=")

    assert tracing_states, "LLM이 호출되지 않아 검증이 무의미하다"
    assert all(state is False for state in tracing_states), (
        f"OCR 체인의 LLM 호출이 트레이싱된다(개인정보 외부 전송): {tracing_states}"
    )


@patch("ai.chains.report_chain.get_llm")
def test_report_chain_disables_tracing_during_llm_calls(mock_get_llm):
    """보고서 프롬프트의 고객사 정보(시설명·위치·하자내용)가 전송되지 않아야 한다."""
    tracing_states: list = []
    mock_get_llm.return_value = _tracing_recording_llm(tracing_states)

    with tracing_context(enabled=True):
        try:
            run_report_chain(facility_info={}, confirmed_defects=[])
        except Exception:  # noqa: BLE001 — MagicMock 응답이라 조립 단계에서 깨질 수 있다.
            pass  # 검증 대상은 "호출 시점의 트레이싱 상태"뿐이다.

    assert tracing_states, "LLM이 호출되지 않아 검증이 무의미하다"
    assert all(state is False for state in tracing_states), (
        f"report_chain의 LLM 호출이 트레이싱된다(고객사 정보 외부 전송): {tracing_states}"
    )
