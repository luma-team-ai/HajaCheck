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
 * 공개 응답(BusinessLicenseOcrResponse)에는 4필드만 화이트리스트로 옮겨 담는다.
 *
 * <p>businessStartDate(개업연월일, #598)는 국세청 진위확인(#596)이 요구하는 값이라 FE가 별도 입력
 * 없이 이 OCR 응답으로 자동채움한다. AI 서버가 이미 ISO YYYY-MM-DD로 정규화해 내려주므로(nullable —
 * 인식/파싱 실패 시 null) 이 프록시는 별도 가공 없이 그대로 전달만 한다.
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
            String representativeName,
            String businessStartDate) {
    }

    public record ErrorBody(String code, String message) {
    }
}
