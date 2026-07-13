"""AI 엔드포인트 — 네이밍: /ai/{기능} (AI_개발_컨벤션.md §5)

/ai/report · /ai/chat · /ai/briefing · /ai/defect-explain · /ai/nl-search
장시간 작업(보고서 생성)은 동기 응답 금지 — 비동기 잡 패턴(잡 ID -> 폴링)
"""
from fastapi import APIRouter
from pydantic import BaseModel

from ai.chains.briefing_chain import DashboardStats, run_briefing_chain
from ai.chains.defect_explain_chain import run_defect_explain_chain
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


@router.post("/briefing")
def briefing(req: DashboardStats) -> AIResponse:
    """대시보드 AI 주간 브리핑 — 현황 데이터 → 자연어 요약(수치는 코드 계산)."""
    try:
        result, facts = run_briefing_chain(req)
    except Exception as e:  # noqa: BLE001 — 스키마 파싱 실패·타임아웃 등 표준 폴백
        return AIResponse.fail(AIErrorCode.LLM_INVALID_OUTPUT, str(e))
    return AIResponse.ok({**result.model_dump(), "facts": facts.model_dump()})
