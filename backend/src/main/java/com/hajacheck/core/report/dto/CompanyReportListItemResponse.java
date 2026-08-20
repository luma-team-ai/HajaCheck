package com.hajacheck.core.report.dto;

import com.hajacheck.core.report.entity.Report;
import com.hajacheck.core.report.entity.ReportStatus;
import java.time.LocalDateTime;
import java.util.Map;

/** 회사 보고서 목록 항목. */
public record CompanyReportListItemResponse(
        Long id, Long inspectionId, Long facilityId, String facilityName, int roundNo,
        Map<String, Long> gradeDistribution, ReportStatus status, int version,
        LocalDateTime updatedAt, String pdfUrl) {

    public static CompanyReportListItemResponse from(Report report, Map<String, Long> gradeDistribution) {
        return new CompanyReportListItemResponse(report.getId(), report.getInspectionId(),
                report.getInspection().getFacilityId(), report.getInspection().getFacility().getName(),
                report.getRoundNo(), gradeDistribution, report.getStatus(),
                report.getVersion(), report.getUpdatedAt(), report.getPdfUrl());
    }
}
