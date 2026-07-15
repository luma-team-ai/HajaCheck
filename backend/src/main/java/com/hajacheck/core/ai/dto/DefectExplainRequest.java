package com.hajacheck.core.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * 프론트가 보내는 하자 원인·조치방안 설명 요청. JSON 키는 FastAPI 계약과 동일하게 snake_case 유지
 * (프론트 PR2가 baseURL만 바꾸도록 — #228 handoff). 동일 @JsonProperty 로 FastAPI 호출 시 바디 직렬화에도
 * 그대로 재사용된다(AiProxyService 참고).
 */
public record DefectExplainRequest(
        @NotBlank @JsonProperty("defect_type") String defectType,
        @NotBlank @JsonProperty("severity_grade") String severityGrade,
        @NotBlank String location,
        @NotBlank @JsonProperty("facility_type") String facilityType) {
}
