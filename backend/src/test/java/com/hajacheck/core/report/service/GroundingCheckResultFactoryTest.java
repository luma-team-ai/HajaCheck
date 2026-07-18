package com.hajacheck.core.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.core.ai.dto.ReportResponse;
import com.hajacheck.core.report.entity.GroundingCheckResult;
import com.hajacheck.core.report.entity.GroundingRequestContext;
import com.hajacheck.global.exception.DomainValidationException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GroundingCheckResultFactoryTest {

    private static final String REPORT_CONTENT_HASH =
            "4a1bb364dd04353d066273d3347a7a6702b98150d0a24d1a21f3229be6ccfdcd";

    private static final String REPORT_CONTENT_HASH_LEGAL_BASIS_UNVERIFIED =
            "0f99c606207c20c65edb341fd60bb9b927fb50e60cecc234d529a6cd236e17ee";

    @Test
    void fromAiReport_Python전체payload를DTO왕복한뒤공식저장JSON해시를검증() throws Exception {
        GroundingRequestContext context = new GroundingRequestContext("request-123", 10L, 3);
        String aiDataJson = """
                {
                  "overview":{"purpose":"purpose","facility_summary":"facility","scope":"all"},
                  "summary":{"overall_opinion":"caution","total_count":1,
                    "count_by_grade":{"A":0,"B":1,"C":0,"D":0,"E":0},"key_findings":["crack"]},
                  "detail":{"items":[{"defect_type":"crack","location":"floor-1","severity_grade":"B",
                    "description":"micro crack","cause":"shrinkage"}]},
                  "recommendation":{"items":[{"target":"crack","method":"epoxy","priority":"medium",
                    "legal_basis":"article-1","legal_basis_verified":true}],"monitoring_points":["crack area"]},
                  "grounding_ok":true,"grounding_request_id":"request-123","inspection_id":10,
                  "report_version":3,"content_hash":"4a1bb364dd04353d066273d3347a7a6702b98150d0a24d1a21f3229be6ccfdcd"
                }
                """;
        ReportResponse response = new ObjectMapper().readValue(aiDataJson, ReportResponse.class);

        String storedContent = GroundingReportContentSerializer.serialize(response);
        GroundingCheckResult result = GroundingCheckResultFactory.fromAiReport(
                context, response, "[]");

        assertThat(response.recommendation().items().get(0).legalBasisVerified()).isTrue();
        assertThat(storedContent).contains("\"legal_basis_verified\":true");
        assertThat(storedContent).doesNotContain("grounding_request_id", "content_hash", "report_version");
        assertThat(result.target().contentHash()).isEqualTo(REPORT_CONTENT_HASH);
        assertThat(result.passed()).isTrue();
    }

    @Test
    void fromAiReport_legalBasisVerifiedFalse샘플payload를DTO왕복한뒤공식저장JSON해시를검증() throws Exception {
        GroundingRequestContext context = new GroundingRequestContext("request-123", 10L, 3);
        String aiDataJson = """
                {
                  "overview":{"purpose":"purpose","facility_summary":"facility","scope":"all"},
                  "summary":{"overall_opinion":"caution","total_count":1,
                    "count_by_grade":{"A":0,"B":1,"C":0,"D":0,"E":0},"key_findings":["crack"]},
                  "detail":{"items":[{"defect_type":"crack","location":"floor-1","severity_grade":"B",
                    "description":"micro crack","cause":"shrinkage"}]},
                  "recommendation":{"items":[{"target":"crack","method":"epoxy","priority":"medium",
                    "legal_basis":"article-1","legal_basis_verified":false}],"monitoring_points":["crack area"]},
                  "grounding_ok":true,"grounding_request_id":"request-123","inspection_id":10,
                  "report_version":3,"content_hash":"0f99c606207c20c65edb341fd60bb9b927fb50e60cecc234d529a6cd236e17ee"
                }
                """;
        ReportResponse response = new ObjectMapper().readValue(aiDataJson, ReportResponse.class);

        String storedContent = GroundingReportContentSerializer.serialize(response);
        GroundingCheckResult result = GroundingCheckResultFactory.fromAiReport(
                context, response, "[]");

        assertThat(response.recommendation().items().get(0).legalBasisVerified()).isFalse();
        assertThat(storedContent).contains("\"legal_basis_verified\":false");
        assertThat(storedContent).doesNotContain("grounding_request_id", "content_hash", "report_version");
        assertThat(result.target().contentHash()).isEqualTo(REPORT_CONTENT_HASH_LEGAL_BASIS_UNVERIFIED);
        assertThat(result.passed()).isTrue();
    }

    @Test
    void fromAiReport_두요청의비동기응답순서가뒤바뀌면거부() {
        GroundingRequestContext first = GroundingRequestContext.capture(10L, 1);
        GroundingRequestContext second = GroundingRequestContext.capture(10L, 1);
        ReportResponse firstResponse = response(first.groundingRequestId(), 10L, 1, REPORT_CONTENT_HASH);
        ReportResponse secondResponse = response(second.groundingRequestId(), 10L, 1, REPORT_CONTENT_HASH);

        assertThatThrownBy(() -> GroundingCheckResultFactory.fromAiReport(first, secondResponse, null))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> GroundingCheckResultFactory.fromAiReport(second, firstResponse, null))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void fromAiReport_요청ID가다르면거부() {
        GroundingRequestContext context = GroundingRequestContext.capture(10L, 1);
        ReportResponse response = response("different-request", 10L, 1, REPORT_CONTENT_HASH);

        assertThatThrownBy(() -> GroundingCheckResultFactory.fromAiReport(context, response, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("요청 ID");
    }

    @Test
    void fromAiReport_점검ID또는보고서버전이다르면거부() {
        GroundingRequestContext context = GroundingRequestContext.capture(10L, 1);
        ReportResponse wrongInspection = response(
                context.groundingRequestId(), 11L, 1, REPORT_CONTENT_HASH);
        ReportResponse wrongVersion = response(
                context.groundingRequestId(), 10L, 2, REPORT_CONTENT_HASH);

        assertThatThrownBy(() -> GroundingCheckResultFactory.fromAiReport(context, wrongInspection, null))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> GroundingCheckResultFactory.fromAiReport(context, wrongVersion, null))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void fromAiReport_AI계산해시와공식저장payload가다르면거부() {
        GroundingRequestContext context = GroundingRequestContext.capture(10L, 1);
        ReportResponse response = response(
                context.groundingRequestId(), 10L, 1, "different-hash");

        assertThatThrownBy(() -> GroundingCheckResultFactory.fromAiReport(context, response, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("콘텐츠");
    }

    @Test
    void fromAiReport_null응답과nullContext를거부() {
        GroundingRequestContext context = GroundingRequestContext.capture(10L, 1);
        ReportResponse response = response(
                context.groundingRequestId(), 10L, 1, REPORT_CONTENT_HASH);

        assertThatThrownBy(() -> GroundingCheckResultFactory.fromAiReport(context, null, null))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> GroundingCheckResultFactory.fromAiReport(null, response, null))
                .isInstanceOf(DomainValidationException.class);
    }

    private static ReportResponse response(
            String groundingRequestId,
            Long inspectionId,
            Integer reportVersion,
            String contentHash) {
        return new ReportResponse(
                new ReportResponse.Overview("purpose", "facility", "all"),
                new ReportResponse.Summary(
                        "caution",
                        1,
                        Map.of("A", 0, "B", 1, "C", 0, "D", 0, "E", 0),
                        List.of("crack")),
                new ReportResponse.Detail(List.of(new ReportResponse.DetailItem(
                        "crack", "floor-1", "B", "micro crack", "shrinkage"))),
                new ReportResponse.Recommendation(
                        List.of(new ReportResponse.RecommendationItem(
                                "crack", "epoxy", "medium", "article-1", true)),
                        List.of("crack area")),
                true,
                groundingRequestId,
                inspectionId,
                reportVersion,
                contentHash);
    }
}
