package com.hajacheck.core.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * FastAPI {@code POST /ai/report} 원본 응답 envelope (HTTP 200 고정, success 플래그로 성공/실패 구분).
 * <pre>
 * 성공:   { "success": true,  "data": {overview,summary,detail,recommendation,grounding_ok}, "usage": {...} }
 * LLM실패: { "success": false, "data": null, "usage": null, "error": {"code": "...", "message": "..."} }
 * </pre>
 * usage 는 이 프록시 범위에서 미사용 — {@code ignoreUnknown=true} 로 무시.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReportAiEnvelope(
        boolean success,
        ReportResponse data,
        ErrorBody error) {

    public record ErrorBody(String code, String message) {
    }
}
