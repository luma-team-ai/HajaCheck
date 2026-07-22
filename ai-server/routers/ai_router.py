"""AI 엔드포인트 — 네이밍: /ai/{기능} (AI_개발_컨벤션.md §5, v0.2)

/ai/report · /ai/chat · /ai/briefing · /ai/defect-explain · /ai/nl-search · /ai/grounding-check

동기/비동기 경계(§5 v0.2, HAJA-208): 비동기 잡 패턴(잡 ID -> 폴링) 원칙은 클라이언트 대면
경계(예: 백엔드 /api/reports)에 적용된다. 이 라우터의 /ai/* 는 verify_internal_key로 보호되는
내부 전용 엔드포인트 — 그 경계 안쪽(백엔드가 잡 워커에서 호출)이므로 동기 응답이 정상이다.
/ai/report가 4개 섹션을 동기로 생성해 반환하는 것은 설계 위반이 아니라 의도된 구조.
"""
import hashlib
import json
import logging
from typing import Optional

from fastapi import APIRouter, Depends
from langchain_core.exceptions import OutputParserException
from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic import ValidationError as PydanticValidationError

from deps import verify_internal_key

from ai.chains.briefing_chain import DashboardStats, run_briefing_chain
from ai.chains.business_license_ocr_chain import (
    MAX_IMAGE_BASE64_LENGTH,
    run_business_license_ocr_chain,
)
from ai.chains.defect_explain_chain import run_defect_explain_chain
from ai.chains.report_chain import FACILITY_FIELD_LABELS, run_report_chain
from ai.core.grounding import (
    GroundingClaims,
    GroundingDefect,
    MismatchPolicy,
    check_grounding,
)
from ai.core.schemas import AIErrorCode, AIResponse

logger = logging.getLogger(__name__)

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
    except Exception:  # noqa: BLE001 — 스키마 파싱 실패·타임아웃 등 표준 폴백
        # 원본 예외 문자열(str(e))을 그대로 클라이언트에 반환하면 환경변수명·파일경로 등 내부 정보가
        # 노출될 수 있다(PR머신 3차 리뷰 지적) — 서버 로그에만 스택트레이스를 남기고, 클라이언트에는
        # 고정 메시지만 내려준다.
        logger.exception("POST /ai/defect-explain 처리 중 예상치 못한 예외 발생")
        return AIResponse.fail(AIErrorCode.LLM_INVALID_OUTPUT, "하자 설명 생성 중 오류가 발생했습니다")
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
    except Exception:  # noqa: BLE001 — 스키마 파싱 실패·타임아웃 등 표준 폴백
        # defect-explain과 동일 이유로 str(e) 직접 노출 금지 — 고정 메시지 + 서버 로그(PR머신 3차 리뷰 지적)
        logger.exception("POST /ai/briefing 처리 중 예상치 못한 예외 발생")
        return AIResponse.fail(AIErrorCode.LLM_INVALID_OUTPUT, "브리핑 생성 중 오류가 발생했습니다")
    return AIResponse.ok({**result.model_dump(), "facts": facts.model_dump()})


class ConfirmedDefectInput(BaseModel):
    """confirmed_defects 배열 원소 — report_chain 전반에서 `d.get(key, '-')`로 조용히 기본값
    처리되던 필드 존재·타입을 요청 단계(Pydantic)에서 검증한다(PR머신 P2 후속)."""

    defect_type: str = Field(min_length=1)
    location: str = Field(min_length=1)
    severity_grade: str = Field(min_length=1)
    description: str = Field(min_length=1)


class FacilityInfoInput(BaseModel):
    """facility_info 요청 검증 (PR머신 3차 리뷰 지적: raw dict 그대로 노출되던 것을 Pydantic으로 승격).

    report_chain.FACILITY_FIELD_LABELS(name/location/facility_type/scale/inspection_date)를
    알려진 필드로 선언하되, `_format_facility_info`는 알려지지 않은 추가 키도 그대로 렌더링하는
    open-ended 설계이므로 `extra="allow"`로 계약을 깨지 않는다. 다만 값 타입은 스칼라(str/int/float/
    None)로 제한해 dict/list 같은 비스칼라 값이 프롬프트에 섞여 들어가는 걸 막는다.
    """

    model_config = ConfigDict(extra="allow")

    name: Optional[str] = None
    location: Optional[str] = None
    facility_type: Optional[str] = None
    scale: Optional[str] = None
    inspection_date: Optional[str] = None

    @model_validator(mode="before")
    @classmethod
    def _reject_non_scalar(cls, data):
        # field_validator("*")는 명시적으로 선언된 필드에만 적용되고 extra="allow"로 수집되는
        # 알려지지 않은 키에는 적용되지 않는다(Pydantic v2 동작 확인됨) — 모델 전체를 보는
        # model_validator(mode="before")로 원본 dict의 모든 키(알려진 필드 + extra 전부)를 검사한다.
        if isinstance(data, dict):
            for key, value in data.items():
                if value is not None and not isinstance(value, (str, int, float)):
                    raise ValueError(
                        f"facility_info.{key} 값은 문자열/숫자만 허용됩니다(dict/list 등 비스칼라 불가)"
                    )
        return data


if not set(FACILITY_FIELD_LABELS) <= set(FacilityInfoInput.model_fields):
    raise RuntimeError(
        "FACILITY_FIELD_LABELS와 FacilityInfoInput 필드가 어긋났습니다 — 라벨 매핑 동기화 필요"
    )


class ReportRequest(BaseModel):
    """AI 보고서 생성 요청 (AP-040, contract.md `POST /ai/report`)."""

    facility_info: FacilityInfoInput
    confirmed_defects: list[ConfirmedDefectInput] = Field(max_length=100)
    on_mismatch: MismatchPolicy = MismatchPolicy.REGENERATE
    grounding_request_id: Optional[str] = Field(default=None, min_length=1)
    inspection_id: Optional[int] = Field(default=None, ge=1)
    report_version: Optional[int] = Field(default=None, ge=1)

    @model_validator(mode="after")
    def _require_complete_grounding_correlation(self):
        correlation = (
            self.grounding_request_id,
            self.inspection_id,
            self.report_version,
        )
        if any(value is not None for value in correlation) and not all(
            value is not None for value in correlation
        ):
            raise ValueError("grounding correlation 필드는 모두 함께 제공해야 합니다")
        return self


def _canonical_content_hash(report_content: dict) -> str:
    canonical = json.dumps(
        report_content,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


@router.post("/report")
def report(req: ReportRequest) -> AIResponse:
    """AI 보고서 4섹션(개요/요약/상세/권고) 병렬 생성 + Grounding Check (FR-5, HAJA-31)."""
    confirmed_defects = [d.model_dump() for d in req.confirmed_defects]
    facility_info = req.facility_info.model_dump(exclude_none=True)
    try:
        result = run_report_chain(facility_info, confirmed_defects, req.on_mismatch.value)
    except OutputParserException:
        # OutputParserException은 ValueError의 서브클래스라 (ValueError, PydanticValidationError)절보다
        # 먼저 잡아야 한다 — _StructuredLLM.invoke()가 MAX_RETRIES 소진 후 던지는, LLM이 malformed/
        # incomplete JSON을 뱉은 "진짜 LLM 출력 파싱 실패" 케이스(contract.md 기준 LLM_INVALID_OUTPUT).
        # 아래 VALIDATION_ERROR절로 잘못 흡수되면 가장 흔한 실패 유형이 오분류된다(P1 회귀, 코드리뷰 지적).
        # str(e)에는 LLM raw 출력이 담길 수 있어 클라이언트에 반환하지 않는다(타 엔드포인트·generic 핸들러와
        # 동일하게 상세는 서버 로그로만, PR머신 P2 지적 — 예외 메시지 비노출 일관성).
        logger.exception("POST /ai/report — LLM 출력 파싱 실패(OutputParserException)")
        return AIResponse.fail(AIErrorCode.LLM_INVALID_OUTPUT, "보고서 생성 결과를 처리하지 못했습니다")
    except (ValueError, PydanticValidationError):
        # 비-LLM 검증 실패 — detail.items 개수 불일치(ValueError, run_report_chain 자체 검증)나
        # confirmed_defects의 잘못된 severity_grade(GroundingDefect validator 실패) 등.
        # LLM 호출·파싱과 무관한 입력/코드 검증 오류이므로 /ai/grounding-check와 동일하게 VALIDATION_ERROR.
        # str(e)에는 내부 검증 상세가 담길 수 있어 클라이언트에 반환하지 않는다(PR머신 P2 지적 — 비노출 일관성).
        logger.exception("POST /ai/report — 비-LLM 검증 실패(ValueError/PydanticValidationError)")
        return AIResponse.fail(AIErrorCode.VALIDATION_ERROR, "보고서 생성 입력이 올바르지 않습니다")
    except Exception as e:  # noqa: BLE001 — LLM 클라이언트 오류부터 코드 버그까지 포괄하는 최종 폴백.
        # 위 절들이 예측 가능한 LLM/검증 실패를 이미 걸러냈으므로, 여기 도달하는 예외는 네트워크 오류
        # 등 인프라성 실패이거나 실제 프로그래밍 버그일 수 있다 — 어느 쪽이든 스택트레이스를 남겨
        # 클라이언트에는 조용히 LLM_INVALID_OUTPUT으로 보이는 실패가 서버 로그에서는 추적 가능해야 한다.
        logger.exception("POST /ai/report 처리 중 예상치 못한 예외 발생")
        return AIResponse.fail(AIErrorCode.LLM_INVALID_OUTPUT, "보고서 생성 중 오류가 발생했습니다")
    if req.grounding_request_id is not None:
        result = {
            **result,
            "grounding_request_id": req.grounding_request_id,
            "inspection_id": req.inspection_id,
            "report_version": req.report_version,
            "content_hash": _canonical_content_hash(result),
        }
    return AIResponse.ok(result)


class BusinessLicenseOcrRequest(BaseModel):
    """사업자등록증 OCR 요청 — image_base64 경로만 구현(HAJA-169/#552). file_ref는 seam only(미구현)."""

    # max_length=MAX_IMAGE_BASE64_LENGTH(14_000_000): FE 업로드 상한 10MB
    # (BUSINESS_LICENSE_MAX_SIZE_BYTES)의 base64 인코딩 상당치(원본 대비 약 +33%, 여유 포함) —
    # 상한 없이 대형 페이로드를 받으면 디코드/OCR 단계에서 CPU·메모리를 고갈시킬 수 있다
    # (코드 리뷰 P2, DoS 방지). 초과 시 FastAPI가 요청 파싱 단계에서 422로 거부한다(다른 필드의
    # Field(min_length=...) 검증과 동일한 계층 — ConfirmedDefectInput 참고).
    image_base64: Optional[str] = Field(default=None, max_length=MAX_IMAGE_BASE64_LENGTH)
    file_ref: Optional[str] = None


@router.post("/business-license-ocr")
def business_license_ocr(req: BusinessLicenseOcrRequest) -> AIResponse:
    """사업자등록증 OCR (HAJA-169/#552, RapidOCR→EasyOCR 교체 #605) — EasyOCR(한국어 모델)
    + LLM structured output(오탈자 교정 포함).

    image_base64만 지원한다(file_ref는 아직 미구현 — 값이 와도 무시). 이미지가 없으면
    VALIDATION_ERROR. OCR 디코딩 실패·모델 로드 실패·LLM 파싱 실패 등은 예외를 삼키지 않고
    서버 로그에 스택을 남긴 뒤(OCR 원문·개인정보는 로그에 남기지 않음) 클라이언트에는 고정
    메시지로 LLM_INVALID_OUTPUT을 반환한다 — 클라이언트는 수동 입력으로 폴백 가능해야 한다.
    """
    if not req.image_base64:
        return AIResponse.fail(
            AIErrorCode.VALIDATION_ERROR,
            "image_base64가 필요합니다(file_ref는 아직 지원하지 않습니다)",
        )
    try:
        result = run_business_license_ocr_chain(req.image_base64)
    except Exception:  # noqa: BLE001 — OCR/LLM 실패 전반의 표준 폴백(다른 엔드포인트와 동일 패턴)
        logger.exception("POST /ai/business-license-ocr 처리 중 예상치 못한 예외 발생")
        return AIResponse.fail(
            AIErrorCode.LLM_INVALID_OUTPUT, "사업자등록증 인식 중 오류가 발생했습니다"
        )
    return AIResponse.ok(
        {
            "businessRegistrationNumber": result.business_registration_number,
            "companyName": result.company_name,
            "representativeName": result.representative_name,
            "businessStartDate": result.business_start_date,
            "raw": {
                "lineCount": result.line_count,
                "avgConfidence": result.avg_confidence,
            },
            "stub": False,
        }
    )
