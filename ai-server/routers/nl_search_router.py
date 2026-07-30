"""하자 자연어 검색 내부 엔드포인트 — POST /ai/nl-search (HAJA-120/179~183)

ai_router.py와 별도 라우터로 분리한 이유: 이 경로는 contract.md/openapi.yaml에서
X-Internal-Key가 아니라 전용 X-Internal-Service-Token(InternalServiceToken 시큐리티 스킴)으로
보호하도록 명시돼 있다(Spring이 세션 인증·점검자 역할·has_ai_addon 게이트를 모두 통과시킨
요청만 호출하는 경로라, 로그인만 확인되면 프록시하는 다른 /ai/* 엔드포인트와 신뢰 경계가 다르다).
ai_router는 라우터 레벨에서 verify_internal_key를 전체 라우트에 일괄 적용하므로, 이 라우트를
같은 라우터에 추가하면 X-Internal-Key도 함께 요구하게 돼 계약과 어긋난다.
"""
import logging
from datetime import date, datetime
from typing import Any
from zoneinfo import ZoneInfo

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from deps import verify_internal_service_token

from ai.chains.nl_search_chain import run_nl_search_chain
from ai.core.schemas import AIErrorCode, AIResponse

logger = logging.getLogger(__name__)

QUERY_MAX_LENGTH = 500

router = APIRouter(prefix="/ai", tags=["ai", "defect"], dependencies=[Depends(verify_internal_service_token)])


class NlSearchRequest(BaseModel):
    # query를 Any로 선언 — str이 아닌 타입(숫자·객체 등)이 와도 Pydantic이 요청 파싱 단계에서
    # 거부(FastAPI 기본 422 detail[] 응답)하지 않고 라우터 안에서 직접 AIResponse.fail(VALIDATION_ERROR)
    # envelope으로 응답하게 한다(docs/design/ai/nl_search_filter_schema.md §2.1 — "Pydantic 파싱
    # 실패도 동일 envelope 보장", 리뷰 P2: Optional[str]이면 타입 불일치 시 이 보장이 깨졌었다).
    query: Any = None
    # Spring이 KST 기준일을 전달한다. 점진 배포 중 구버전 Spring 호출도 수용하기 위해 선택값으로
    # 두되, 값이 있으면 YYYY-MM-DD 형식을 엄격히 검증한다.
    referenceDate: Any = None


def _resolve_reference_date(raw_value: Any) -> date:
    if raw_value is None:
        return datetime.now(ZoneInfo("Asia/Seoul")).date()
    if not isinstance(raw_value, str):
        raise ValueError("referenceDate는 YYYY-MM-DD 문자열이어야 합니다")
    try:
        parsed = date.fromisoformat(raw_value)
    except ValueError as exc:
        raise ValueError("referenceDate는 YYYY-MM-DD 형식이어야 합니다") from exc
    # date.fromisoformat은 일부 확장 ISO 표현도 받으므로 공개 계약 형식을 고정한다.
    if parsed.isoformat() != raw_value:
        raise ValueError("referenceDate는 YYYY-MM-DD 형식이어야 합니다")
    return parsed


@router.post("/nl-search")
def nl_search(req: NlSearchRequest) -> AIResponse:
    query = (req.query.strip() if isinstance(req.query, str) else "")
    if not query or len(query) > QUERY_MAX_LENGTH:
        return AIResponse.fail(AIErrorCode.VALIDATION_ERROR, "질의는 1~500자여야 합니다")

    try:
        reference_date = _resolve_reference_date(req.referenceDate)
    except ValueError as exc:
        return AIResponse.fail(AIErrorCode.VALIDATION_ERROR, str(exc))
    except Exception:  # noqa: BLE001 — ZoneInfoNotFoundError(tzdata 미탑재 런타임) 등 예상치 못한
        # 예외까지 포괄하는 표준 폴백(다른 엔드포인트와 동일 정책). ValueError가 아니면 여기로 떨어져
        # AIResponse envelope 없이 500이 새는 것을 막는다.
        logger.exception("POST /ai/nl-search 기준일 결정 중 예상치 못한 예외 발생")
        return AIResponse.fail(AIErrorCode.LLM_INVALID_OUTPUT, "자연어 검색 변환 중 오류가 발생했습니다")

    try:
        result = run_nl_search_chain(query, reference_date)
    except Exception:  # noqa: BLE001 — 스키마 파싱 실패·타임아웃 등 표준 폴백(defect_explain과 동일 정책)
        logger.exception("POST /ai/nl-search 처리 중 예상치 못한 예외 발생")
        return AIResponse.fail(AIErrorCode.LLM_INVALID_OUTPUT, "자연어 검색 변환 중 오류가 발생했습니다")
    return AIResponse.ok(result.model_dump(mode="json"))
