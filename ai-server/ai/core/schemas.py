"""공통 요청/응답 Pydantic 모델 — AI_개발_컨벤션.md §5

성공: { "success": true, "data": {...}, "usage": { "tokens": 1234 } }
실패: { "success": false, "error": { "code": "LLM_TIMEOUT", "message": "..." } }
"""
from enum import Enum
from typing import Any, Literal, Optional

from pydantic import BaseModel, Field


class AIErrorCode(str, Enum):
    LLM_TIMEOUT = "LLM_TIMEOUT"
    LLM_RATE_LIMIT = "LLM_RATE_LIMIT"
    LLM_INVALID_OUTPUT = "LLM_INVALID_OUTPUT"  # 스키마 파싱 실패
    RAG_NO_RESULT = "RAG_NO_RESULT"
    VALIDATION_ERROR = "VALIDATION_ERROR"  # 비-LLM 코드 경로(입력·대조 검증) 실패 — grounding 등


class AIError(BaseModel):
    code: AIErrorCode
    message: str


class AIUsage(BaseModel):
    tokens: int


class AIResponse(BaseModel):
    success: bool
    data: Optional[Any] = None
    usage: Optional[AIUsage] = None
    error: Optional[AIError] = None

    @classmethod
    def ok(cls, data: Any, tokens: int = 0) -> "AIResponse":
        return cls(success=True, data=data, usage=AIUsage(tokens=tokens))

    @classmethod
    def fail(cls, code: AIErrorCode, message: str) -> "AIResponse":
        return cls(success=False, error=AIError(code=code, message=message))


class SourceCitation(BaseModel):
    """RAG 답변이 인용한 문서 1건 — docs/design/ai/rag_chroma_schema.md §6 계약.

    locator는 렌더링이 끝난 표시 문구("제12조", "12페이지" 등)이며, 답변 생성
    시점에 1회 조립해 채운다 — 화면 표시 때마다 Chroma를 재조회하지 않는다.
    """

    doc_id: str = Field(pattern=r"^[1-9][0-9]*$")
    title: str = Field(min_length=1)
    collection: Literal["regulations", "defect_kb"]
    locator: str = Field(min_length=1)
    snippet: str = Field(min_length=1)
    chunk_ref: str = Field(min_length=1)


class RagAnswerData(BaseModel):
    """FR-6 RAG 챗봇 답변 — AIResponse.data에 담기는 형태(HAJA-145)."""

    answer: str
    sources: list[SourceCitation]
