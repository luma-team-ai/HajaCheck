package com.hajacheck.core.report.repository;

public interface CompanyReportSummaryProjection {
    long getTotalCount();
    long getFinalizedCount();
    long getDraftCount();
    long getIssuedThisMonthCount();
}
