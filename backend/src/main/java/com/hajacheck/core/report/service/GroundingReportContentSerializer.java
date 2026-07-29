package com.hajacheck.core.report.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.core.ai.dto.ReportResponse;
import com.hajacheck.global.exception.DomainValidationException;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    public static String serialize(ReportResponse aiReport, Set<String> sections, Boolean includePhoto) {
        if (aiReport == null) {
            throw new DomainValidationException("AI 보고서 grounding 결과는 필수다");
        }
        try {
            return MAPPER.writeValueAsString(Content.from(aiReport, ReportOptions.from(sections, includePhoto)));
        } catch (JsonProcessingException e) {
            throw new DomainValidationException("AI 보고서 저장 payload를 직렬화할 수 없다");
        }
    }

    private record Content(
            ReportResponse.Overview overview,
            ReportResponse.Summary summary,
            ReportResponse.Detail detail,
            ReportResponse.Recommendation recommendation,
            @JsonProperty("grounding_ok") boolean groundingOk,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            ReportOptions reportOptions) {

        private static Content from(ReportResponse response) {
            return new Content(
                    response.overview(),
                    response.summary(),
                    response.detail(),
                    response.recommendation(),
                    response.groundingOk(),
                    null);
        }

        private static Content from(ReportResponse response, ReportOptions options) {
            Set<String> selected = options.selectedSections();
            boolean includeSummary = selected.contains("summary");
            boolean includeOpinion = selected.contains("opinion");
            return new Content(
                    selected.contains("overview") ? response.overview() : emptyOverview(),
                    includeSummary || includeOpinion ? summaryForOptions(response.summary(), includeSummary, includeOpinion) : emptySummary(),
                    selected.contains("details") ? response.detail() : emptyDetail(),
                    selected.contains("recommendation") ? response.recommendation() : emptyRecommendation(),
                    response.groundingOk(),
                    options);
        }
    }

    private record ReportOptions(
            List<String> sections,
            Boolean includePhoto) {

        private static final Set<String> ALL_SECTIONS = Set.of("overview", "summary", "details", "recommendation", "opinion");

        private static ReportOptions from(Set<String> sections, Boolean includePhoto) {
            Set<String> effectiveSections = sections == null ? ALL_SECTIONS : sections;
            List<String> normalizedSections = effectiveSections.stream()
                            .filter(ALL_SECTIONS::contains)
                            .sorted()
                            .toList();
            if (normalizedSections.isEmpty()) {
                throw new DomainValidationException("보고서에는 최소 1개 섹션이 포함되어야 한다");
            }
            return new ReportOptions(
                    normalizedSections,
                    includePhoto == null || includePhoto);
        }

        private Set<String> selectedSections() {
            return Set.copyOf(sections);
        }
    }

    private static ReportResponse.Overview emptyOverview() {
        return new ReportResponse.Overview("", "", "");
    }

    private static ReportResponse.Summary emptySummary() {
        return new ReportResponse.Summary("", 0, Map.of(), List.of());
    }

    private static ReportResponse.Summary summaryForOptions(
            ReportResponse.Summary source, boolean includeSummary, boolean includeOpinion) {
        if (source == null) {
            return emptySummary();
        }
        return new ReportResponse.Summary(
                includeOpinion ? source.overallOpinion() : "",
                includeSummary ? source.totalCount() : 0,
                includeSummary ? source.countByGrade() : Map.of(),
                includeSummary ? source.keyFindings() : List.of());
    }

    private static ReportResponse.Detail emptyDetail() {
        return new ReportResponse.Detail(List.of());
    }

    private static ReportResponse.Recommendation emptyRecommendation() {
        return new ReportResponse.Recommendation(List.of(), List.of());
    }
}
