"""AI 엔드포인트 — 네이밍: /ai/{기능} (AI_개발_컨벤션.md §5)

/ai/report · /ai/chat · /ai/briefing · /ai/defect-explain · /ai/nl-search · /ai/grounding-check
장시간 작업(보고서 생성)은 동기 응답 금지 — 비동기 잡 패턴(잡 ID -> 폴링)
"""
from typing import Optional

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from deps import verify_internal_key

from ai.chains.briefing_chain import DashboardStats, run_briefing_chain
from ai.chains.defect_explain_chain import run_defect_explain_chain
from ai.chains.report_chain import run_report_chain
from ai.core.grounding import (
    GroundingClaims,
    GroundingDefect,
    MismatchPolicy,
    check_grounding,
)
from ai.core.schemas import AIErrorCode, AIResponse

# dependencies=[Depends(verify_internal_key)] — /ai/* 전 라우트에 내부키 검증 일괄 적용.
# (/health는 main.py에 prefix 없이 정의돼 이 라우터 밖 → 무인증 유지, 컨테이너 헬스체크용)
router = APIRouter(
    prefix="/ai", tags=["ai"], dependencies=[Depends(verify_internal_key)]
)


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
    except Exception as e:  # noqa: BLE001 — 방어적 폴백(LLM 무관 코드 경로, 통상 예외 없음)
        # grounding 은 LLM 호출이 없는 순수 코드 대조 → LLM 에러코드 대신 범용 VALIDATION_ERROR 사용
        return AIResponse.fail(AIErrorCode.VALIDATION_ERROR, str(e))
    return AIResponse.ok(result.model_dump())


@router.post("/briefing")
def briefing(req: DashboardStats) -> AIResponse:
    """대시보드 AI 주간 브리핑 — 현황 데이터 → 자연어 요약(수치는 코드 계산)."""
    try:
        result, facts = run_briefing_chain(req)
    except Exception as e:  # noqa: BLE001 — 스키마 파싱 실패·타임아웃 등 표준 폴백
        return AIResponse.fail(AIErrorCode.LLM_INVALID_OUTPUT, str(e))
    return AIResponse.ok({**result.model_dump(), "facts": facts.model_dump()})


class ReportRequest(BaseModel):
    """AI 보고서 생성 요청 (AP-040, contract.md `POST /ai/report`)."""

    facility_info: dict
    confirmed_defects: list[dict]
    on_mismatch: MismatchPolicy = MismatchPolicy.REGENERATE


@router.post("/report")
def report(req: ReportRequest) -> AIResponse:
    """AI 보고서 4섹션(개요/요약/상세/권고) 병렬 생성 + Grounding Check (FR-5, HAJA-31)."""
    try:
        result = run_report_chain(req.facility_info, req.confirmed_defects, req.on_mismatch.value)
    except Exception as e:  # noqa: BLE001 — 스키마 파싱 실패·detail 개수 불일치 등 표준 폴백
        return AIResponse.fail(AIErrorCode.LLM_INVALID_OUTPUT, str(e))
    return AIResponse.ok(result)


class BusinessLicenseOcrRequest(BaseModel):
    """사업자등록증 OCR 요청 — stub 단계는 내용 미사용(seam only)."""

    image_base64: Optional[str] = None
    file_ref: Optional[str] = None


@router.post("/business-license-ocr")
def business_license_ocr(req: BusinessLicenseOcrRequest) -> AIResponse:
    """사업자등록증 OCR — stub. 실제 OCR 미구현, 향후 교체 예정(HAJA-169).

    입력(image_base64/file_ref)은 현재 사용하지 않으며, 계약된 고정 stub 응답만 반환한다.
    """
    return AIResponse.ok(
        {
            "businessRegistrationNumber": None,
            "companyName": None,
            "representativeName": None,
            "raw": {},
            "stub": True,
        }
    )
