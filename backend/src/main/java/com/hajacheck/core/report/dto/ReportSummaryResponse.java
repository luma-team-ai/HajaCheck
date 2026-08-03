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
        LocalDateTime createdAt,
        String createdByName) {

    // createdByName은 Report.createdBy(userId)를 이름으로 변환한 값 — User 조회가 필요해
    // 호출부(ReportService)가 배치 조회 후 넘겨준다. 조회 실패/탈퇴 등으로 못 찾으면 null이며,
    // 프론트는 이 경우 "알 수 없음"으로 표시한다(신규 필드 추가, DB 마이그레이션 없음).
    public static ReportSummaryResponse from(Report report, String createdByName) {
        return new ReportSummaryResponse(
                report.getId(),
                report.getInspectionId(),
                report.getVersion(),
                report.getStatus(),
                report.getGroundingCheckPassed(),
                report.getCreatedAt(),
                createdByName);
    }
}
