package com.hajacheck.core.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * FastAPI {@code POST /ai/briefing} 원본 응답 envelope (HTTP 200 고정, success 플래그로 성공/실패 구분).
 * <pre>
 * 성공:   { "success": true,  "data": {briefing,recommendation,facts:{...}}, "usage": {...} }
 * LLM실패: { "success": false, "error": {"code": "...", "message": "..."} }
 * </pre>
 * usage 는 이 프록시 범위에서 미사용 — {@code ignoreUnknown=true} 로 무시.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BriefingAiEnvelope(
        boolean success,
        BriefingResponse data,
        ErrorBody error) {

    public record ErrorBody(String code, String message) {
    }
}
