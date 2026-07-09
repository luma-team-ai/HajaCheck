"""하자 원인·조치방안 설명 체인 (점검B 담당, FR-4 P1)

AI_개발_컨벤션.md §8 예시 체인 절차를 따름.
"""
from pathlib import Path

from pydantic import BaseModel

from ai.core.llm_client import get_llm

PROMPTS_DIR = Path(__file__).resolve().parent.parent / "prompts"


class DefectExplain(BaseModel):
    """LLM 응답은 structured output 으로만 수신 — 자유 텍스트 파싱 금지"""
    cause: str  # 추정 원인
    risk: str  # 방치 시 위험
    action: str  # 조치 방안


def _build_prompt(defect_type: str, severity_grade: str, location: str, facility_type: str) -> str:
    system = (PROMPTS_DIR / "_system_base.md").read_text(encoding="utf-8")
    template = (PROMPTS_DIR / "defect_explain.md").read_text(encoding="utf-8")
    filled = template.format(
        defect_type=defect_type,
        severity_grade=severity_grade,
        location=location,
        facility_type=facility_type,
    )
    return f"{system}\n\n{filled}"


def run_defect_explain_chain(
    defect_type: str, severity_grade: str, location: str, facility_type: str
) -> DefectExplain:
    prompt = _build_prompt(defect_type, severity_grade, location, facility_type)
    llm = get_llm().with_structured_output(DefectExplain)
    return llm.invoke(prompt)
