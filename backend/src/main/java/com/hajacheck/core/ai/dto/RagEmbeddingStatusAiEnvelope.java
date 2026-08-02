package com.hajacheck.core.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * FastAPI {@code GET /ai/rag-documents/{doc_id}/embedding-status} 원본 응답 envelope(#1328,
 * HTTP 200 고정, success 플래그로 성공/실패 구분) — RagEmbedAiEnvelope과 동일 스켈레톤.
 * <pre>
 * 성공: { "success": true,  "data": {"chunk_count": 5}, "usage": {...} }
 * 실패: { "success": false, "error": {"code": "...", "message": "..."} }
 * </pre>
 * usage 는 이 프록시 범위에서 미사용 — {@code ignoreUnknown=true} 로 무시.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RagEmbeddingStatusAiEnvelope(
        boolean success,
        RagEmbeddingStatusResponse data,
        ErrorBody error) {

    public record ErrorBody(String code, String message) {
    }
}
