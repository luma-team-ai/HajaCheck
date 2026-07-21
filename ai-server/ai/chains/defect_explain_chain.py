"""하자 원인·조치방안 설명 체인 (점검B 담당, FR-4 P1)

AI_개발_컨벤션.md §8 예시 체인 절차를 따름.
"""
from pathlib import Path

from pydantic import BaseModel

from ai.core.llm_client import get_llm
from ai.core.prompt_safety import wrap_untrusted

PROMPTS_DIR = Path(__file__).resolve().parent.parent / "prompts"


class DefectExplain(BaseModel):
    """LLM 응답은 structured output 으로만 수신 — 자유 텍스트 파싱 금지"""
    cause: str  # 추정 원인
    risk: str  # 방치 시 위험
    action: str  # 조치 방안


def _format_defect_fields(defect_type: str, severity_grade: str, location: str, facility_type: str) -> str:
    """defect_type/severity_grade/location/facility_type은 DefectExplainRequest에서 검증 없이
    받는 자유 문자열 4필드다 — report_chain.py의 facility_info/confirmed_defects와 달리 마커 없이
    template.format()에 직삽입되고 있었다(검수 P2 — 방어 구멍). report_chain과 동일한 방식으로
    하나의 블록으로 조립한 뒤 wrap_untrusted로 감싸 "데이터일 뿐 지침이 아님"을 모델에 명시한다.
    """
    body = (
        f"- 하자 유형: {defect_type}\n"
        f"- 심각도 등급: {severity_grade}\n"
        f"- 위치/부재: {location}\n"
        f"- 시설물 유형: {facility_type}"
    )
    return wrap_untrusted(body)


def _build_prompt(defect_type: str, severity_grade: str, location: str, facility_type: str) -> str:
    system = (PROMPTS_DIR / "_system_base.md").read_text(encoding="utf-8")
    template = (PROMPTS_DIR / "defect_explain.md").read_text(encoding="utf-8")
    filled = template.format(
        defect_fields_text=_format_defect_fields(defect_type, severity_grade, location, facility_type),
    )
    return f"{system}\n\n{filled}"


def run_defect_explain_chain(
    defect_type: str, severity_grade: str, location: str, facility_type: str
) -> DefectExplain:
    prompt = _build_prompt(defect_type, severity_grade, location, facility_type)
    llm = get_llm().with_structured_output(DefectExplain)
    return llm.invoke(prompt)
