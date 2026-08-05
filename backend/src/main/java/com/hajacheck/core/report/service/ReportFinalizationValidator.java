package com.hajacheck.core.report.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.Set;

/**
 * 보고서 최종 확정 시 선택된 섹션의 필수 항목 작성 여부를 검증한다.
 */
@Component
public class ReportFinalizationValidator {

    private final ObjectMapper objectMapper;
    private static final Set<String> ALL_SECTIONS = Set.of("overview", "summary", "details", "recommendation");

    public ReportFinalizationValidator() {
        this(new ObjectMapper());
    }

    public ReportFinalizationValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public void validate(String contentJson) {
        if (!StringUtils.hasText(contentJson)) {
            throw new BusinessException(ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING);
        }

        try {
            JsonNode root = objectMapper.readTree(contentJson);
            Set<String> enabledSections = getEnabledSections(root);

            if (enabledSections.contains("overview")) {
                validateOverview(root.path("overview"));
            }
            if (enabledSections.contains("summary")) {
                validateSummary(root.path("summary"));
            }
            if (enabledSections.contains("details")) {
                validateDetails(root.path("detail"));
            }
            if (enabledSections.contains("recommendation")) {
                validateRecommendation(root.path("recommendation"));
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING, e);
        }
    }

    private Set<String> getEnabledSections(JsonNode root) {
        JsonNode sectionsNode = root.path("reportOptions").path("sections");
        if (sectionsNode.isArray() && !sectionsNode.isEmpty()) {
            Set<String> sections = new HashSet<>();
            for (JsonNode s : sectionsNode) {
                if (s.isTextual()) {
                    sections.add(s.asText());
                }
            }
            return sections;
        }
        return ALL_SECTIONS;
    }

    private void validateOverview(JsonNode overview) {
        if (!hasText(overview.path("purpose").asText())
                || !hasText(overview.path("facility_summary").asText())
                || !hasText(overview.path("scope").asText())) {
            throw new BusinessException(ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING);
        }
    }

    private void validateSummary(JsonNode summary) {
        if (!hasText(summary.path("overall_opinion").asText())) {
            throw new BusinessException(ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING);
        }
    }

    private void validateDetails(JsonNode detail) {
        JsonNode items = detail.path("items");
        if (!items.isArray() || items.isEmpty()) {
            throw new BusinessException(ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING);
        }
        for (JsonNode item : items) {
            if (!hasText(item.path("description").asText()) || !hasText(item.path("cause").asText())) {
                throw new BusinessException(ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING);
            }
        }
    }

    private void validateRecommendation(JsonNode recommendation) {
        JsonNode items = recommendation.path("items");
        if (!items.isArray() || items.isEmpty()) {
            throw new BusinessException(ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING);
        }
        for (JsonNode item : items) {
            if (!hasText(item.path("method").asText())) {
                throw new BusinessException(ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING);
            }
        }
    }

    private boolean hasText(String value) {
        return value != null && StringUtils.hasText(value);
    }
}
