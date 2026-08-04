package com.hajacheck.core.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * FastAPI {@code DELETE /ai/rag-documents/{doc_id}} 원본 응답 envelope (HTTP 200 고정, success
 * 플래그로 성공/실패 구분) — #1394.
 * <pre>
 * 성공: { "success": true,  "data": {"doc_id": "42"} }
 * 실패: { "success": false, "error": {"code": "...", "message": "..."} }
 * </pre>
 * 호출부(RagDocumentService)는 성공 여부만 필요해 data는 매핑하지 않는다 —
 * {@code ignoreUnknown=true}로 무시(RagEmbedAiEnvelope와 동일 패턴).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RagDeleteAiEnvelope(
        boolean success,
        ErrorBody error) {

    public record ErrorBody(String code, String message) {
    }
}
