package com.hajacheck.core.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * FastAPI {@code POST /ai/business-license-ocr} 원본 응답 envelope(#557 / HAJA-324, HTTP 200 고정,
 * success 플래그로 성공/실패 구분).
 * <pre>
 * 성공:   { "success": true,  "data": {businessRegistrationNumber,companyName,representativeName,raw,stub} }
 * OCR/LLM실패: { "success": false, "error": {"code": "...", "message": "..."} }
 * </pre>
 * data 의 raw(OCR 신뢰도 등)·stub 필드는 이 프록시 범위에서 미사용 — {@code ignoreUnknown=true} 로 무시하고,
 * 공개 응답(BusinessLicenseOcrResponse)에는 3필드만 화이트리스트로 옮겨 담는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BusinessLicenseOcrAiEnvelope(
        boolean success,
        Data data,
        ErrorBody error) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(
            String businessRegistrationNumber,
            String companyName,
            String representativeName) {
    }

    public record ErrorBody(String code, String message) {
    }
}
