"""점검 목록 자연어 검색을 의미 수준의 필터 의도로 변환한다.

LLM은 DB 쿼리나 기준일에 종속된 상대 날짜를 만들지 않는다. 캐시 가능한 ``NlSearchIntentV2``를
반환하고, 상대 날짜와 숫자 연산자는 Python 정규화 단계에서 최종 필터로 변환한다.

``query``는 사용자 자유 입력(1~500자)이라 회사명·시설명이 섞여 들어올 수 있으므로 LangSmith
트레이싱에서 제외한다(rag_chat_chain의 고객 질의와 동일한 취급).
"""

import calendar
from datetime import date, timedelta
from pathlib import Path
from typing import Literal, Optional

from langsmith.run_helpers import tracing_context
from pydantic import BaseModel, Field, model_validator

from ai.core.llm_client import get_llm
from ai.core.prompt_safety import wrap_untrusted

PROMPTS_DIR = Path(__file__).resolve().parent.parent / "prompts"

DefectTypeCode = Literal["CRACK", "SPALLING", "LEAK_EFFLORESCENCE", "REBAR_EXPOSURE", "PAINT_DAMAGE"]
DefectGradeCode = Literal["A", "B", "C", "D", "E"]
DefectStatusCode = Literal["DETECTED", "CONFIRMED", "IN_PROGRESS", "RESOLVED"]
InspectionTypeCode = Literal["REGULAR", "DETAILED", "EMERGENCY"]
InspectionStatusCode = Literal["CREATED", "UPLOADING", "ANALYZING", "ANALYZED", "REVIEWED", "REPORTED"]
NumericOperator = Literal["EXACT", "GTE", "LTE", "BETWEEN"]


class DateIntent(BaseModel):
    """LLM이 해석한 날짜 의미. 상대 날짜는 기준일 없이 보존한다."""

    kind: Literal["ABSOLUTE_RANGE", "ROLLING_PAST"]
    dateFrom: Optional[date] = None
    dateTo: Optional[date] = None
    amount: Optional[int] = Field(default=None, ge=1)
    unit: Optional[Literal["DAY", "WEEK", "MONTH"]] = None

    @model_validator(mode="after")
    def validate_shape(self) -> "DateIntent":
        if self.kind == "ABSOLUTE_RANGE":
            if self.dateFrom is None or self.dateTo is None:
                raise ValueError("ABSOLUTE_RANGE에는 dateFrom과 dateTo가 모두 필요합니다")
            if self.dateFrom > self.dateTo:
                raise ValueError("dateFrom은 dateTo보다 늦을 수 없습니다")
            if self.amount is not None or self.unit is not None:
                raise ValueError("ABSOLUTE_RANGE에는 amount/unit을 사용할 수 없습니다")
        else:
            if self.amount is None or self.unit is None:
                raise ValueError("ROLLING_PAST에는 amount와 unit이 모두 필요합니다")
            if self.dateFrom is not None or self.dateTo is not None:
                raise ValueError("ROLLING_PAST에는 절대 날짜를 사용할 수 없습니다")
        return self


class RoundIntent(BaseModel):
    operator: NumericOperator
    value: int = Field(ge=1)
    maxValue: Optional[int] = Field(default=None, ge=1)

    @model_validator(mode="after")
    def validate_range(self) -> "RoundIntent":
        return _validate_numeric_range(self)


class DefectCountIntent(BaseModel):
    operator: NumericOperator
    value: int = Field(ge=0)
    maxValue: Optional[int] = Field(default=None, ge=0)

    @model_validator(mode="after")
    def validate_range(self) -> "DefectCountIntent":
        return _validate_numeric_range(self)


def _validate_numeric_range(intent):
    if intent.operator == "BETWEEN":
        if intent.maxValue is None:
            raise ValueError("BETWEEN에는 maxValue가 필요합니다")
        if intent.value > intent.maxValue:
            raise ValueError("value는 maxValue보다 클 수 없습니다")
    elif intent.maxValue is not None:
        raise ValueError("BETWEEN이 아닌 연산자에는 maxValue를 사용할 수 없습니다")
    return intent


class NlSearchIntentV2(BaseModel):
    """LLM structured output 및 캐시 단위. 최종 조회 파라미터와 의도적으로 분리한다."""

    intentVersion: Literal["2"]
    type: list[DefectTypeCode] = Field(default_factory=list)
    grade: list[DefectGradeCode] = Field(default_factory=list)
    defectStatus: list[DefectStatusCode] = Field(default_factory=list)
    confidenceMin: Optional[float] = Field(default=None, ge=0.0, le=1.0)
    inspectionType: list[InspectionTypeCode] = Field(default_factory=list)
    inspectionStatus: list[InspectionStatusCode] = Field(default_factory=list)
    inspectionDate: Optional[DateIntent] = None
    roundNo: Optional[RoundIntent] = None
    defectCount: Optional[DefectCountIntent] = None
    unsupported_terms: list[str] = Field(default_factory=list)
    clarifying_question: Optional[str] = None
    interpretation_confidence: float = Field(ge=0.0, le=1.0)


class NlSearchFilters(BaseModel):
    """Spring과 프론트가 사용하는 최종 필터 계약."""

    type: list[DefectTypeCode] = Field(default_factory=list)
    grade: list[DefectGradeCode] = Field(default_factory=list)
    status: list[DefectStatusCode] = Field(default_factory=list)
    confidenceMin: Optional[float] = Field(default=None, ge=0.0, le=1.0)
    inspectionType: list[InspectionTypeCode] = Field(default_factory=list)
    inspectionStatus: list[InspectionStatusCode] = Field(default_factory=list)
    inspectionDateFrom: Optional[date] = None
    inspectionDateTo: Optional[date] = None
    roundNoMin: Optional[int] = Field(default=None, ge=1)
    roundNoMax: Optional[int] = Field(default=None, ge=1)
    defectCountMin: Optional[int] = Field(default=None, ge=0)
    defectCountMax: Optional[int] = Field(default=None, ge=0)


class NlSearchResult(BaseModel):
    filters: NlSearchFilters
    unsupported_terms: list[str] = Field(default_factory=list)
    clarifying_question: Optional[str] = None
    interpretation_confidence: float = Field(ge=0.0, le=1.0)


def _subtract_months(value: date, months: int) -> date:
    month_index = value.year * 12 + value.month - 1 - months
    year, zero_based_month = divmod(month_index, 12)
    month = zero_based_month + 1
    day = min(value.day, calendar.monthrange(year, month)[1])
    return date(year, month, day)


def _resolve_date_range(intent: DateIntent, reference_date: date) -> tuple[date, date]:
    if intent.kind == "ABSOLUTE_RANGE":
        if intent.dateFrom is None or intent.dateTo is None:
            raise ValueError("검증되지 않은 절대 날짜 의도입니다")
        return intent.dateFrom, intent.dateTo

    if intent.amount is None:
        raise ValueError("검증되지 않은 상대 날짜 의도입니다")
    if intent.unit == "MONTH":
        start = _subtract_months(reference_date, intent.amount)
    elif intent.unit == "WEEK":
        start = reference_date - timedelta(weeks=intent.amount)
    else:
        start = reference_date - timedelta(days=intent.amount)
    return start, reference_date


def _resolve_numeric(intent: RoundIntent | DefectCountIntent | None) -> tuple[int | None, int | None]:
    if intent is None:
        return None, None
    if intent.operator == "EXACT":
        return intent.value, intent.value
    if intent.operator == "GTE":
        return intent.value, None
    if intent.operator == "LTE":
        return None, intent.value
    return intent.value, intent.maxValue


def normalize_nl_search_intent(intent: NlSearchIntentV2, reference_date: date) -> NlSearchResult:
    """캐시된 의미를 요청별 기준일에 맞춘 최종 필터로 변환한다."""

    date_from: date | None = None
    date_to: date | None = None
    if intent.inspectionDate is not None:
        date_from, date_to = _resolve_date_range(intent.inspectionDate, reference_date)

    round_min, round_max = _resolve_numeric(intent.roundNo)
    count_min, count_max = _resolve_numeric(intent.defectCount)

    filters = NlSearchFilters(
        type=list(dict.fromkeys(intent.type)),
        grade=list(dict.fromkeys(intent.grade)),
        status=list(dict.fromkeys(intent.defectStatus)),
        confidenceMin=intent.confidenceMin,
        inspectionType=list(dict.fromkeys(intent.inspectionType)),
        inspectionStatus=list(dict.fromkeys(intent.inspectionStatus)),
        inspectionDateFrom=date_from,
        inspectionDateTo=date_to,
        roundNoMin=round_min,
        roundNoMax=round_max,
        defectCountMin=count_min,
        defectCountMax=count_max,
    )
    return NlSearchResult(
        filters=filters,
        unsupported_terms=intent.unsupported_terms,
        clarifying_question=intent.clarifying_question,
        interpretation_confidence=intent.interpretation_confidence,
    )


def _build_prompt(query: str) -> str:
    system = (PROMPTS_DIR / "_system_base.md").read_text(encoding="utf-8")
    template = (PROMPTS_DIR / "nl_search_convert.md").read_text(encoding="utf-8")
    filled = template.format(query_text=wrap_untrusted(query))
    return f"{system}\n\n{filled}"


def run_nl_search_chain(query: str, reference_date: date) -> NlSearchResult:
    """LLM 의미 해석 결과를 기준일에 맞춰 최종 필터로 정규화한다."""

    prompt = _build_prompt(query)
    # 사용자 자유 입력 외부 전송 차단 — 모듈 docstring 참고.
    # 전역 LANGCHAIN_TRACING_V2 값과 무관하게 항상 비전송(트레이싱이 꺼져 있어도 무해).
    with tracing_context(enabled=False):
        intent = get_llm().with_structured_output(NlSearchIntentV2).invoke(prompt)
    return normalize_nl_search_intent(intent, reference_date)
