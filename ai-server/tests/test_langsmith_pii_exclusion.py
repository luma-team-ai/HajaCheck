"""민감 정보가 LangSmith로 전송되지 않는지 검증.

아래 체인들은 프롬프트에 민감 정보를 그대로 담으므로 `tracing_context(enabled=False)`로
감싸 트레이싱에서 제외한다.
- business_license_ocr_chain: 사업자등록번호·대표자명 (개인정보)
- report_chain: 시설명·위치·하자내용 (고객사 정보)
- rag_chat_chain: 고객 질의 원문 (회사명·시설명이 섞여 들어올 수 있음)
- briefing_chain: 회사 현황 수치·주요 하자유형 (조합 시 회사 식별 가능)

이 테스트는 전역 트레이싱이 켜진 상태를 가정하고, LLM 호출이 실제로 일어나는 시점에
트레이싱이 꺼져 있는지를 확인한다 — 감싸는 코드를 나중에 지우면 여기서 실패한다.

conftest.py가 테스트 프로세스의 LANGCHAIN_TRACING_V2를 false로 강제하므로, 각 테스트는
tracing_context(enabled=True)로 "켜진 상태"를 명시적으로 재현한 뒤 검증한다.
"""
import threading
from unittest.mock import MagicMock, patch

from langsmith.run_helpers import get_tracing_context, tracing_context

from ai.chains.briefing_chain import DashboardStats, run_briefing_chain
from ai.chains.business_license_ocr_chain import (
    BusinessLicenseOcrExtract,
    run_business_license_ocr_chain,
)
from ai.chains.rag_chat_chain import run_rag_chat_chain
from ai.chains.report_chain import run_report_chain


def _tracing_recording_llm(recorder: list, response=None) -> MagicMock:
    """invoke() 시점의 (스레드명, 트레이싱 활성 여부)를 recorder에 남기는 가짜 LLM."""

    def _record_and_return(_prompt):
        recorder.append((threading.current_thread().name, get_tracing_context()["enabled"]))
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
    assert all(enabled is False for _thread, enabled in tracing_states), (
        f"OCR 체인의 LLM 호출이 트레이싱된다(개인정보 외부 전송): {tracing_states}"
    )


@patch("ai.chains.report_chain.get_llm")
def test_report_chain_disables_tracing_during_llm_calls(mock_get_llm):
    """보고서 프롬프트의 고객사 정보(시설명·위치·하자내용)가 전송되지 않아야 한다.

    4섹션은 RunnableParallel(ThreadPoolExecutor)로 병렬 실행되므로, 워커 스레드까지
    tracing_context가 전파되는지가 이 가드의 핵심이다 — contextvars는 스레드에 자동
    상속되지 않아, 컨텍스트를 복사하지 않는 실행기로 바뀌면 조용히 새어나간다.
    """
    tracing_states: list = []
    mock_get_llm.return_value = _tracing_recording_llm(tracing_states)

    with tracing_context(enabled=True):
        try:
            run_report_chain(facility_info={"name": "테스트시설"}, confirmed_defects=[])
        except Exception:  # noqa: BLE001 — MagicMock 응답이라 조립 단계에서 깨질 수 있다.
            pass  # 검증 대상은 "호출 시점의 트레이싱 상태"뿐이다.

    assert tracing_states, "LLM이 호출되지 않아 검증이 무의미하다"
    assert all(enabled is False for _thread, enabled in tracing_states), (
        f"report_chain의 LLM 호출이 트레이싱된다(고객사 정보 외부 전송): {tracing_states}"
    )
    # 병렬 경로를 실제로 지나갔는지 확인 — 전부 MainThread면 위 단언이 스레드 전파를
    # 검증하지 못한 것이므로, 그 사실을 드러내 테스트가 헛돌지 않게 한다.
    assert any(not thread.startswith("MainThread") for thread, _enabled in tracing_states), (
        f"워커 스레드에서의 호출이 없어 contextvar 전파가 검증되지 않았다: {tracing_states}"
    )


@patch("ai.chains.rag_chat_chain.get_vectorstore")
@patch("ai.chains.rag_chat_chain.get_redis_client")
@patch("ai.chains.rag_chat_chain.get_llm")
def test_rag_chat_chain_disables_tracing_during_llm_call(mock_get_llm, mock_redis, mock_vs):
    """고객 질의 원문(회사명·시설명이 섞일 수 있음)이 전송되지 않아야 한다."""
    mock_redis.return_value.get.return_value = None  # 캐시 미스 → LLM 경로로 진입
    mock_vs.return_value.similarity_search.return_value = [MagicMock(page_content="법규 발췌")]
    tracing_states: list = []
    mock_get_llm.return_value = _tracing_recording_llm(tracing_states)

    with tracing_context(enabled=True):
        try:
            run_rag_chat_chain("OO건설 3층 외벽 균열 처리 방법")
        except Exception:  # noqa: BLE001 — MagicMock 응답이라 후속 조립이 깨질 수 있다.
            pass

    assert tracing_states, "LLM이 호출되지 않아 검증이 무의미하다"
    assert all(enabled is False for _thread, enabled in tracing_states), (
        f"rag_chat_chain의 LLM 호출이 트레이싱된다(고객 질의 외부 전송): {tracing_states}"
    )


@patch("ai.chains.briefing_chain.get_llm")
def test_briefing_chain_disables_tracing_during_llm_call(mock_get_llm):
    """회사 현황 수치·주요 하자유형(조합 시 회사 식별 가능)이 전송되지 않아야 한다."""
    tracing_states: list = []
    mock_get_llm.return_value = _tracing_recording_llm(tracing_states)
    stats = DashboardStats(
        total_facilities=12,
        monthly_analysis=74,
        pending_review=83,
        pending_action=0,
        this_week_defects=680,
        last_week_defects=169,
        top_defect_type="균열",
        critical_defects=112,
    )

    with tracing_context(enabled=True):
        try:
            run_briefing_chain(stats)
        except Exception:  # noqa: BLE001 — MagicMock 응답이라 후속 조립이 깨질 수 있다.
            pass

    assert tracing_states, "LLM이 호출되지 않아 검증이 무의미하다"
    assert all(enabled is False for _thread, enabled in tracing_states), (
        f"briefing_chain의 LLM 호출이 트레이싱된다(회사 정보 외부 전송): {tracing_states}"
    )
