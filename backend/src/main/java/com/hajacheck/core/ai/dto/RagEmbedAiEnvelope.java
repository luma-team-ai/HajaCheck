package com.hajacheck.core.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * FastAPI {@code POST /ai/rag-documents/embed} 원본 응답 envelope (HTTP 200 고정, success 플래그로
 * 성공/실패 구분) — #22/HAJA-35.
 * <pre>
 * 성공: { "success": true,  "data": {"chunk_count": 12}, "usage": {...} }
 * 실패: { "success": false, "error": {"code": "...", "message": "..."} }
 * </pre>
 * usage 는 이 프록시 범위에서 미사용 — {@code ignoreUnknown=true} 로 무시.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RagEmbedAiEnvelope(
        boolean success,
        RagEmbedResponse data,
        ErrorBody error) {

    public record ErrorBody(String code, String message) {
    }
}
