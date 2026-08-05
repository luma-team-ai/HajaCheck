package com.hajacheck.core.report.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 보고서 최종 확정 시 선택된 자동 섹션과 수동 섹션의 필수 항목 작성 여부를 검증한다.
 */
@Component
public class ReportFinalizationValidator {

    private final ObjectMapper objectMapper;
    private static final Set<String> ALLOWED_SECTIONS = Set.of("overview", "summary", "details", "recommendation");
    private static final List<String> REQUIRED_SUBMISSION_FIELDS = List.of(
            "recipient", "contractDate", "companyName", "companyAddress", "representativeName");
    private static final List<String> REQUIRED_PARTICIPANT_FIELDS = List.of(
            "role", "name", "qualification", "period");

    public ReportFinalizationValidator() {
        this(new ObjectMapper());
    }

    public ReportFinalizationValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public void validate(String contentJson) {
        if (!StringUtils.hasText(contentJson)) {
            throw new BusinessException(
                    ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING,
                    "보고서 본문이 비어 있거나 JSON 형식이 올바르지 않습니다.");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(contentJson);
        } catch (Exception e) {
            throw new BusinessException(
                    ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING,
                    "보고서 본문 JSON 형식이 올바르지 않습니다.", e);
        }

        if (root == null || !root.isObject()) {
            throw new BusinessException(
                    ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING,
                    "보고서 본문 구조가 올바르지 않습니다.");
        }

        Set<String> enabledSections = parseAndValidateEnabledSections(root);

        validateOverview(root.path("overview"));
        validateSummary(root.path("summary"));

        if (enabledSections.contains("details")) {
            validateDetails(root.path("detail"));
        }
        if (enabledSections.contains("recommendation")) {
            validateRecommendation(root.path("recommendation"));
        }

        validateManualSections(root.path("manualSections"));
    }

    private Set<String> parseAndValidateEnabledSections(JsonNode root) {
        JsonNode sectionsNode = root.path("reportOptions").path("sections");
        if (sectionsNode.isArray() && !sectionsNode.isEmpty()) {
            Set<String> sections = new HashSet<>();
            for (JsonNode s : sectionsNode) {
                if (s.isTextual()) {
                    String sectionName = s.asText();
                    if (!ALLOWED_SECTIONS.contains(sectionName)) {
                        throw new BusinessException(
                                ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING,
                                "알 수 없는 섹션 값이 포함되어 있습니다: " + sectionName);
                    }
                    sections.add(sectionName);
                } else {
                    throw new BusinessException(
                            ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING,
                            "reportOptions.sections에 유효하지 않은 항목이 포함되어 있습니다.");
                }
            }

            if (!sections.contains("overview")) {
                throw new BusinessException(
                        ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING,
                        "필수 섹션(overview)이 누락되었습니다.");
            }
            if (!sections.contains("summary")) {
                throw new BusinessException(
                        ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING,
                        "필수 섹션(summary)이 누락되었습니다.");
            }

            return sections;
        }

        return ALLOWED_SECTIONS;
    }

    private void validateOverview(JsonNode overview) {
        if (!hasText(overview.path("purpose").asText(null))
                || !hasText(overview.path("facility_summary").asText(null))
                || !hasText(overview.path("scope").asText(null))) {
            throw new BusinessException(
                    ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING,
                    "기본현황 필수 항목(점검 목적, 시설물 개요, 점검 범위)이 누락되었습니다.");
        }
    }

    private void validateSummary(JsonNode summary) {
        if (!hasText(summary.path("overall_opinion").asText(null))) {
            throw new BusinessException(
                    ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING,
                    "결과 요약 필수 항목(종합 의견)이 누락되었습니다.");
        }
    }

    private void validateDetails(JsonNode detail) {
        JsonNode items = detail.path("items");
        if (!items.isArray() || items.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING,
                    "진단 외관조사결과 항목이 선택되었으나 하자 항목이 없습니다.");
        }
        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i);
            if (!hasText(item.path("description").asText(null))
                    || !hasText(item.path("cause").asText(null))) {
                throw new BusinessException(
                        ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING,
                        "진단 외관조사결과 하자 #" + (i + 1) + "의 필수 항목(설명, 원인 분석)이 누락되었습니다.");
            }
        }
    }

    private void validateRecommendation(JsonNode recommendation) {
        JsonNode items = recommendation.path("items");
        if (!items.isArray() || items.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING,
                    "보수ㆍ보강(안) 항목이 선택되었으나 권고 항목이 없습니다.");
        }
        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i);
            if (!hasText(item.path("method").asText(null))) {
                throw new BusinessException(
                        ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING,
                        "보수ㆍ보강(안) 권고 #" + (i + 1) + "의 필수 항목(방법)이 누락되었습니다.");
            }
        }
    }

    private void validateManualSections(JsonNode manualSections) {
        if (manualSections.isMissingNode() || manualSections.isNull()) {
            return;
        }
        if (!manualSections.isArray()) {
            throw new BusinessException(
                    ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING,
                    "manualSections 구조가 올바르지 않습니다.");
        }

        for (JsonNode section : manualSections) {
            String type = section.path("type").asText("");
            JsonNode data = section.path("data");

            if ("submission".equals(type)) {
                for (String field : REQUIRED_SUBMISSION_FIELDS) {
                    if (!hasText(data.path(field).asText(null))) {
                        throw new BusinessException(
                                ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING,
                                "제출문 수동 섹션의 필수 항목(" + field + ")이 누락되었습니다.");
                    }
                }
            } else if ("participants".equals(type)) {
                JsonNode entries = data.path("entries");
                if (!entries.isArray() || entries.isEmpty()) {
                    throw new BusinessException(
                            ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING,
                            "참여자 수동 섹션 항목이 없습니다.");
                }

                int nonInitialCount = 0;
                for (JsonNode entry : entries) {
                    boolean hasAnyValue = REQUIRED_PARTICIPANT_FIELDS.stream()
                            .anyMatch(field -> hasText(entry.path(field).asText(null)));
                    if (hasAnyValue) {
                        nonInitialCount++;
                        for (String field : REQUIRED_PARTICIPANT_FIELDS) {
                            if (!hasText(entry.path(field).asText(null))) {
                                throw new BusinessException(
                                        ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING,
                                        "참여자 수동 섹션 항목의 필수 필드(" + field + ")가 누락되었습니다.");
                            }
                        }
                    }
                }

                if (nonInitialCount == 0) {
                    throw new BusinessException(
                            ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING,
                            "참여자 수동 섹션에 입력된 항목이 없습니다.");
                }
            } else if ("location-drawing-photos".equals(type)) {
                JsonNode images = data.path("images");
                if (!images.isArray() || images.isEmpty()) {
                    throw new BusinessException(
                            ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING,
                            "위치/도면/사진 수동 섹션에 사진이 등록되지 않았습니다.");
                }
            } else {
                if (!hasText(data.path("body").asText(null))) {
                    throw new BusinessException(
                            ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING,
                            "추가 수동 섹션 본문이 누락되었습니다.");
                }
            }
        }
    }

    private boolean hasText(String value) {
        return value != null && StringUtils.hasText(value.trim());
    }
}
