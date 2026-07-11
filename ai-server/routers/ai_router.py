"""AI 엔드포인트 — 네이밍: /ai/{기능} (AI_개발_컨벤션.md §5)

/ai/report · /ai/chat · /ai/briefing · /ai/defect-explain · /ai/nl-search
장시간 작업(보고서 생성)은 동기 응답 금지 — 비동기 잡 패턴(잡 ID -> 폴링)
"""
from fastapi import APIRouter
from pydantic import BaseModel

from ai.chains.defect_explain_chain import run_defect_explain_chain
from ai.core.grounding import (
    GroundingClaims,
    GroundingDefect,
    MismatchPolicy,
    check_grounding,
)
from ai.core.schemas import AIErrorCode, AIResponse

router = APIRouter(prefix="/ai", tags=["ai"])


@router.get("/ping")
def ping() -> AIResponse:
    """공통 envelope 동작 확인용 — 실제 엔드포인트는 각 담당이 추가"""
    return AIResponse.ok({"message": "pong"})


class DefectExplainRequest(BaseModel):
    defect_type: str
    severity_grade: str
    location: str
    facility_type: str


@router.post("/defect-explain")
def defect_explain(req: DefectExplainRequest) -> AIResponse:
    try:
        result = run_defect_explain_chain(
            req.defect_type, req.severity_grade, req.location, req.facility_type
        )
    except Exception as e:  # noqa: BLE001 — 스키마 파싱 실패·타임아웃 등 표준 폴백
        return AIResponse.fail(AIErrorCode.LLM_INVALID_OUTPUT, str(e))
    return AIResponse.ok(result.model_dump())


class GroundingCheckRequest(BaseModel):
    """생성물 주장 수치·등급을 실측 defects와 코드로 대조 (환각 방어 게이트, HAJA-117).

    체인의 structured output에서 claims 를 채워 넘긴다. LLM 호출 없음 — 결정론적.
    """
    defects: list[GroundingDefect]  # 실측 하자 목록 (대조 기준)
    claims: GroundingClaims  # 생성물이 주장하는 수치·등급
    on_mismatch: MismatchPolicy = MismatchPolicy.REGENERATE  # 불일치 조치: regenerate | warn


@router.post("/grounding-check")
def grounding_check(req: GroundingCheckRequest) -> AIResponse:
    try:
        result = check_grounding(req.defects, req.claims, req.on_mismatch)
    except Exception as e:  # noqa: BLE001 — 방어적 폴백(코드 대조라 통상 예외 없음)
        return AIResponse.fail(AIErrorCode.LLM_INVALID_OUTPUT, str(e))
    return AIResponse.ok(result.model_dump())
