"""하자 자연어 검색 → 필터 조건 변환 체인 (HAJA-120/179~183)

docs/design/ai/nl_search_filter_schema.md §2.2/§3을 그대로 구현한다. LLM은 DB를 조회하지 않고
질의를 필터 조건(JSON)으로만 변환한다 — 실제 하자 목록 조회는 Spring Boot GET /api/defects가 담당.
"""
from pathlib import Path
from typing import Literal, Optional

from pydantic import BaseModel, Field

from ai.core.llm_client import get_llm
from ai.core.prompt_safety import wrap_untrusted

PROMPTS_DIR = Path(__file__).resolve().parent.parent / "prompts"

DefectTypeCode = Literal["CRACK", "SPALLING", "LEAK_EFFLORESCENCE", "REBAR_EXPOSURE", "PAINT_DAMAGE"]
DefectGradeCode = Literal["A", "B", "C", "D", "E"]
DefectStatusCode = Literal["DETECTED", "CONFIRMED", "ACTION_PENDING", "IN_PROGRESS", "RESOLVED"]


class NlSearchFilters(BaseModel):
    type: list[DefectTypeCode] = Field(default_factory=list)
    grade: list[DefectGradeCode] = Field(default_factory=list)
    status: list[DefectStatusCode] = Field(default_factory=list)
    confidenceMin: Optional[float] = Field(default=None, ge=0.0, le=1.0)


class NlSearchResult(BaseModel):
    """LLM 응답은 structured output 으로만 수신 — 자유 텍스트 파싱 금지"""

    filters: NlSearchFilters
    unsupported_terms: list[str] = Field(default_factory=list)
    clarifying_question: Optional[str] = None
    interpretation_confidence: float = Field(ge=0.0, le=1.0)


def _build_prompt(query: str) -> str:
    system = (PROMPTS_DIR / "_system_base.md").read_text(encoding="utf-8")
    template = (PROMPTS_DIR / "nl_search_convert.md").read_text(encoding="utf-8")
    # query는 사용자 자유 입력 — defect_explain_chain과 동일하게 UNTRUSTED DATA 마커로 감싸
    # 프롬프트 인젝션을 방어한다(AI_개발_컨벤션.md §0).
    filled = template.format(query_text=wrap_untrusted(query))
    return f"{system}\n\n{filled}"


def run_nl_search_chain(query: str) -> NlSearchResult:
    """query는 호출부(라우터)에서 trim 후 1~500자로 검증된 값만 받는다."""
    prompt = _build_prompt(query)
    llm = get_llm().with_structured_output(NlSearchResult)
    return llm.invoke(prompt)
