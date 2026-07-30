package com.hajacheck.core.report.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.facility.entity.FacilityInitialGrade;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.entity.InspectionType;
import com.hajacheck.core.report.entity.Report;
import com.hajacheck.core.report.entity.ReportStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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
        LocalDateTime createdAt,
        ReportContext context) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ReportDetailResponse from(Report report) {
        return from(report, null);
    }

    public static ReportDetailResponse from(Report report, ReportContext context) {
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
                report.getCreatedAt(),
                context);
    }

    public record ReportContext(
            FacilityContext facility,
            InspectionContext inspection,
            CompanyContext company,
            UserContext assignedInspector,
            UserContext createdByUser,
            List<DefectContext> defects,
            List<MediaContext> media) {
    }

    public record FacilityContext(
            Long id,
            String name,
            String type,
            String address,
            BigDecimal latitude,
            BigDecimal longitude,
            Integer builtYear,
            String scale,
            Integer inspectionCycleMonths,
            LocalDate nextInspectionDueAt,
            FacilityInitialGrade initialGrade,
            Long assigneeUserId,
            String memo,
            String thumbnailUrl) {
    }

    public record InspectionContext(
            Long id,
            Long facilityId,
            Long createdBy,
            Long assignedInspectorId,
            Integer roundNo,
            LocalDate inspectionDate,
            InspectionType type,
            InspectionStatus status,
            LocalDateTime createdAt) {
    }

    public record CompanyContext(
            Long id,
            String name,
            String representativeName,
            String address,
            String addressDetail) {
    }

    public record UserContext(
            Long id,
            String name,
            String role) {
    }

    public record DefectContext(
            Long id,
            DefectType type,
            String typeLabel,
            DefectGrade grade,
            DefectStatus status,
            String location,
            Double confidence,
            Long mediaId,
            Double bboxX,
            Double bboxY,
            Double bboxW,
            Double bboxH,
            Double crackWidthMm,
            Double crackLengthMm,
            Double areaRatio,
            String actionContent,
            LocalDate actionDate,
            Long actionAssigneeId) {
    }

    public record MediaContext(
            Long id,
            Long inspectionId,
            Long facilityId,
            String fileType,
            String thumbnailUrl,
            String detailUrl,
            String originalFilename,
            LocalDateTime capturedAt) {
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
