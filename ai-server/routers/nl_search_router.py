"""하자 자연어 검색 내부 엔드포인트 — POST /ai/nl-search (HAJA-120/179~183)

ai_router.py와 별도 라우터로 분리한 이유: 이 경로는 contract.md/openapi.yaml에서
X-Internal-Key가 아니라 전용 X-Internal-Service-Token(InternalServiceToken 시큐리티 스킴)으로
보호하도록 명시돼 있다(Spring이 세션 인증·점검자 역할·has_ai_addon 게이트를 모두 통과시킨
요청만 호출하는 경로라, 로그인만 확인되면 프록시하는 다른 /ai/* 엔드포인트와 신뢰 경계가 다르다).
ai_router는 라우터 레벨에서 verify_internal_key를 전체 라우트에 일괄 적용하므로, 이 라우트를
같은 라우터에 추가하면 X-Internal-Key도 함께 요구하게 돼 계약과 어긋난다.
"""
import logging
from typing import Optional

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from deps import verify_internal_service_token

from ai.chains.nl_search_chain import run_nl_search_chain
from ai.core.schemas import AIErrorCode, AIResponse

logger = logging.getLogger(__name__)

QUERY_MAX_LENGTH = 500

router = APIRouter(prefix="/ai", tags=["ai", "defect"], dependencies=[Depends(verify_internal_service_token)])


class NlSearchRequest(BaseModel):
    # 필수 필드를 Optional로 선언 — Pydantic 자체 422 대신 라우터에서 직접 AIResponse.fail(VALIDATION_ERROR)
    # envelope으로 응답하기 위함(docs/design/ai/nl_search_filter_schema.md §2.1).
    query: Optional[str] = None


@router.post("/nl-search")
def nl_search(req: NlSearchRequest) -> AIResponse:
    query = (req.query or "").strip()
    if not query or len(query) > QUERY_MAX_LENGTH:
        return AIResponse.fail(AIErrorCode.VALIDATION_ERROR, "질의는 1~500자여야 합니다")

    try:
        result = run_nl_search_chain(query)
    except Exception:  # noqa: BLE001 — 스키마 파싱 실패·타임아웃 등 표준 폴백(defect_explain과 동일 정책)
        logger.exception("POST /ai/nl-search 처리 중 예상치 못한 예외 발생")
        return AIResponse.fail(AIErrorCode.LLM_INVALID_OUTPUT, "자연어 검색 변환 중 오류가 발생했습니다")
    return AIResponse.ok(result.model_dump())
