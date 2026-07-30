package com.hajacheck.core.defect.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * FastAPI {@code POST /ai/nl-search} 원본 응답 envelope — DefectExplainAiEnvelope과 동일 패턴.
 * usage 는 이 프록시 범위에서 미사용 — {@code ignoreUnknown=true} 로 무시.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NlSearchAiEnvelope(
        boolean success,
        NlSearchResult data,
        ErrorBody error) {

    public record ErrorBody(String code, String message) {
    }
}
