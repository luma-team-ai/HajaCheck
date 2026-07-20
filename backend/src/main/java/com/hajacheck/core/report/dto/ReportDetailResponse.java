package com.hajacheck.core.report.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.core.report.entity.Report;
import com.hajacheck.core.report.entity.ReportStatus;
import java.time.LocalDateTime;

/** 보고서 상세 응답 — Entity 직접 노출 금지(§0). content는 저장 시 이미 검증된 JSON이라 파싱해 구조로 내려준다. */
public record ReportDetailResponse(
        Long id,
        Long inspectionId,
        int version,
        JsonNode content,
        ReportStatus status,
        Boolean groundingCheckPassed,
        String pdfUrl,
        Long editedBy,
        Long createdBy,
        LocalDateTime createdAt) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ReportDetailResponse from(Report report) {
        return new ReportDetailResponse(
                report.getId(),
                report.getInspectionId(),
                report.getVersion(),
                parse(report.getContentJson()),
                report.getStatus(),
                report.getGroundingCheckPassed(),
                report.getPdfUrl(),
                report.getEditedBy(),
                report.getCreatedBy(),
                report.getCreatedAt());
    }

    private static JsonNode parse(String contentJson) {
        try {
            return MAPPER.readTree(contentJson);
        } catch (JsonProcessingException e) {
            // Report.draft/updateContent가 저장 전 JsonValidator로 이미 검증하므로 이론상 도달 불가.
            return null;
        }
    }
}
