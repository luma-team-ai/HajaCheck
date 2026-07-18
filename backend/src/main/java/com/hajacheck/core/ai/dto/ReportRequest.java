package com.hajacheck.core.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * AI 보고서 생성 요청(#239 / HAJA-192). JSON 키는 FastAPI 계약과 동일하게 snake_case 유지
 * (docs/api-contract/contract.md "POST /ai/report" 참고). 동일 @JsonProperty 로
 * FastAPI 호출 시 바디 직렬화에도 그대로 재사용된다(AiProxyService 참고).
 */
public record ReportRequest(
        @NotNull @Valid @JsonProperty("facility_info") FacilityInfo facilityInfo,
        @NotEmpty @Valid @JsonProperty("confirmed_defects") List<ConfirmedDefect> confirmedDefects,
        // 허용값은 계약(docs/api-contract/openapi.yaml "on_mismatch" enum)의 regenerate/warn 2종뿐(#334 P3).
        @Pattern(regexp = "^(regenerate|warn)$", message = "on_mismatch 는 regenerate 또는 warn 만 허용됩니다.")
        @JsonProperty("on_mismatch") String onMismatch,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("grounding_request_id") String groundingRequestId,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("inspection_id") Long inspectionId,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("report_version") Integer reportVersion) {

    public ReportRequest(
            FacilityInfo facilityInfo,
            List<ConfirmedDefect> confirmedDefects,
            String onMismatch) {
        this(facilityInfo, confirmedDefects, onMismatch, null, null, null);
    }

    /** on_mismatch 미지정 시 계약 기본값 "regenerate" 로 채운다. */
    public ReportRequest {
        if (onMismatch == null || onMismatch.isBlank()) {
            onMismatch = "regenerate";
        }
    }

    public record FacilityInfo(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 200) String location) {
    }

    public record ConfirmedDefect(
            @NotBlank @Size(max = 50) @JsonProperty("defect_type") String defectType,
            @NotBlank @Size(max = 200) String location,
            @NotBlank @Size(max = 50) @JsonProperty("severity_grade") String severityGrade,
            @NotBlank @Size(max = 1000) String description) {
    }
}
