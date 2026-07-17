package com.hajacheck.core.report.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.core.ai.dto.ReportResponse;
import com.hajacheck.global.exception.DomainValidationException;

/** AI 보고서 응답에서 상관관계 메타데이터를 제외한 저장용 payload를 직렬화한다. */
public final class GroundingReportContentSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GroundingReportContentSerializer() {
    }

    public static String serialize(ReportResponse aiReport) {
        if (aiReport == null) {
            throw new DomainValidationException("AI 보고서 grounding 결과는 필수다");
        }
        try {
            return MAPPER.writeValueAsString(Content.from(aiReport));
        } catch (JsonProcessingException e) {
            throw new DomainValidationException("AI 보고서 저장 payload를 직렬화할 수 없다");
        }
    }

    private record Content(
            ReportResponse.Overview overview,
            ReportResponse.Summary summary,
            ReportResponse.Detail detail,
            ReportResponse.Recommendation recommendation,
            @com.fasterxml.jackson.annotation.JsonProperty("grounding_ok") boolean groundingOk) {

        private static Content from(ReportResponse response) {
            return new Content(
                    response.overview(),
                    response.summary(),
                    response.detail(),
                    response.recommendation(),
                    response.groundingOk());
        }
    }
}
