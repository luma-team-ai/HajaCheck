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

    // 크로스 언어(한국어 실데이터) 콘텐츠 해시 패리티 — Python _canonical_content_hash로
    // 동일 payload를 직접 실행해 구한 실제 해시값(ai-server/tests/test_report.py의
    // test_canonical_content_hash_matches_backend_canonical_sample_korean_real_data와 동일).
    private static final String REPORT_CONTENT_HASH_KOREAN_REAL_DATA =
            "46be3c0fda0f8657d70702dde257dfbf000cf0f23596275fc21dcd494270cb89";

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
    void fromAiReport_한국어실데이터payload를DTO왕복한뒤Python과동일한공식저장JSON해시를검증() throws Exception {
        GroundingRequestContext context = new GroundingRequestContext("request-123", 10L, 3);
        String aiDataJson = """
                {
                  "overview":{"purpose":"정기 안전점검","facility_summary":"철근콘크리트 구조의 5층 근린생활시설",
                    "scope":"전체 구조부 및 마감재"},
                  "summary":{"overall_opinion":"주의","total_count":2,
                    "count_by_grade":{"A":0,"B":1,"C":1,"D":0,"E":0},
                    "key_findings":["건조 수축에 의한 미세 균열","누수 흔적 발견"]},
                  "detail":{"items":[
                    {"defect_type":"균열","location":"지하 1층 주차장 벽체","severity_grade":"B",
                      "description":"건조 수축에 의한 미세 균열이 관찰되었습니다","cause":"콘크리트 양생 중 수분 증발"},
                    {"defect_type":"누수","location":"옥상 방수층","severity_grade":"C",
                      "description":"우천 시 누수가 발생할 우려가 있습니다","cause":"방수층 노후화"}]},
                  "recommendation":{"items":[{"target":"균열","method":"에폭시 주입 보수","priority":"중간",
                    "legal_basis":"건축물관리법 제13조","legal_basis_verified":true}],
                    "monitoring_points":["지하 1층 벽체 균열 진행 여부 정기 관찰"]},
                  "grounding_ok":true,"grounding_request_id":"request-123","inspection_id":10,
                  "report_version":3,"content_hash":"46be3c0fda0f8657d70702dde257dfbf000cf0f23596275fc21dcd494270cb89"
                }
                """;
        ReportResponse response = new ObjectMapper().readValue(aiDataJson, ReportResponse.class);

        String storedContent = GroundingReportContentSerializer.serialize(response);
        GroundingCheckResult result = GroundingCheckResultFactory.fromAiReport(
                context, response, "[]");

        assertThat(storedContent).contains("건조 수축에 의한 미세 균열이 관찰되었습니다");
        assertThat(result.target().contentHash()).isEqualTo(REPORT_CONTENT_HASH_KOREAN_REAL_DATA);
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
