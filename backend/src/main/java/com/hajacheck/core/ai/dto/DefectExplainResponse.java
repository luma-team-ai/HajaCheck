package com.hajacheck.core.ai.dto;

/**
 * 프론트 기대 응답 — FastAPI AIResponse.data 를 그대로 매핑(필드명 동일해 별도 매핑 불필요).
 */
public record DefectExplainResponse(String cause, String risk, String action) {
}
