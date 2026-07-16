package com.hajacheck.core.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * 프론트 기대 응답 — FastAPI AIResponse.data 를 그대로 매핑(docs/api-contract/contract.md
 * "POST /ai/report" 응답 성공 스키마와 1:1, snake_case 필드는 @JsonProperty 로 매핑).
 */
public record ReportResponse(
        Overview overview,
        Summary summary,
        Detail detail,
        Recommendation recommendation,
        @JsonProperty("grounding_ok") boolean groundingOk) {

    public record Overview(
            String purpose,
            @JsonProperty("facility_summary") String facilitySummary,
            String scope) {
    }

    public record Summary(
            @JsonProperty("overall_opinion") String overallOpinion,
            @JsonProperty("total_count") int totalCount,
            @JsonProperty("count_by_grade") Map<String, Integer> countByGrade,
            @JsonProperty("key_findings") List<String> keyFindings) {
    }

    public record Detail(List<DetailItem> items) {
    }

    public record DetailItem(
            @JsonProperty("defect_type") String defectType,
            String location,
            @JsonProperty("severity_grade") String severityGrade,
            String description,
            String cause) {
    }

    public record Recommendation(
            List<RecommendationItem> items,
            @JsonProperty("monitoring_points") List<String> monitoringPoints) {
    }

    public record RecommendationItem(
            String target,
            String method,
            String priority,
            @JsonProperty("legal_basis") String legalBasis) {
    }
}
