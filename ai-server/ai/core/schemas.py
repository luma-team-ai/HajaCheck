"""공통 요청/응답 Pydantic 모델 — AI_개발_컨벤션.md §5

성공: { "success": true, "data": {...}, "usage": { "tokens": 1234 } }
실패: { "success": false, "error": { "code": "LLM_TIMEOUT", "message": "..." } }
"""
from enum import Enum
from typing import Any, Optional

from pydantic import BaseModel


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
