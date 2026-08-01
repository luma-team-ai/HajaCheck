package com.hajacheck.core.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
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
        @JsonProperty("grounding_ok") boolean groundingOk,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("grounding_request_id") String groundingRequestId,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("inspection_id") Long inspectionId,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("report_version") Integer reportVersion,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("content_hash") String contentHash) {

    public ReportResponse(
            Overview overview,
            Summary summary,
            Detail detail,
            Recommendation recommendation,
            boolean groundingOk) {
        this(overview, summary, detail, recommendation, groundingOk, null, null, null, null);
    }

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
            // ai-server가 confirmed_defects의 id를 그대로 echo — 프론트가 인덱스가 아니라 이 값으로
            // 실제 Defect(사진·bbox)와 1:1 매칭한다(#1375). NON_NULL — 이 필드가 없던 구버전 저장분과
            // canonical hash 직렬화(GroundingCheckResultFactory) 호환을 위해 null이면 키 자체를 생략한다.
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @JsonProperty("defect_id") Long defectId,
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
            @JsonProperty("legal_basis") String legalBasis,
            @JsonProperty("legal_basis_verified") boolean legalBasisVerified) {

        public RecommendationItem(
                String target, String method, String priority, String legalBasis) {
            this(target, method, priority, legalBasis, false);
        }
    }
}
