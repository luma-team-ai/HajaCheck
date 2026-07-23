package com.hajacheck.core.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * FastAPI {@code POST /ai/rag-chat} 원본 응답 envelope (HTTP 200 고정, success 플래그로 성공/실패 구분,
 * HAJA-32, #467).
 * <pre>
 * 성공:   { "success": true,  "data": {answer, sources: [...]}, "usage": {...} }
 * 실패:   { "success": false, "error": {"code": "RAG_NO_RESULT" 등, "message": "..."} }
 * </pre>
 * usage 는 이 프록시 범위에서 미사용 — {@code ignoreUnknown=true} 로 무시(DefectExplainAiEnvelope와 동일).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RagChatAiEnvelope(
        boolean success,
        RagChatResponse data,
        ErrorBody error) {

    public record ErrorBody(String code, String message) {
    }
}
