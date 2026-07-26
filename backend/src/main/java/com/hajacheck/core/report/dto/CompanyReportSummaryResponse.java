package com.hajacheck.core.report.dto;

/** 회사 범위 보고서 KPI 요약. */
public record CompanyReportSummaryResponse(
        long totalCount, long finalizedCount, long draftCount, long issuedThisMonthCount) {
}
