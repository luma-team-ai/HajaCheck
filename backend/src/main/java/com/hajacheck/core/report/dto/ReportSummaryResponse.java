package com.hajacheck.core.report.dto;

import com.hajacheck.core.report.entity.Report;
import com.hajacheck.core.report.entity.ReportStatus;
import java.time.LocalDateTime;

/** 보고서 버전 목록용 요약 응답 — Entity 직접 노출 금지(§0). */
public record ReportSummaryResponse(
        Long id,
        Long inspectionId,
        int version,
        ReportStatus status,
        Boolean groundingCheckPassed,
        LocalDateTime createdAt) {

    public static ReportSummaryResponse from(Report report) {
        return new ReportSummaryResponse(
                report.getId(),
                report.getInspectionId(),
                report.getVersion(),
                report.getStatus(),
                report.getGroundingCheckPassed(),
                report.getCreatedAt());
    }
}
