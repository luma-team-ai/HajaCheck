package com.hajacheck.mypage.dto;

/**
 * GET /api/me/inspections/summary 응답(#844, handoff §2-1).
 *
 * <p>issuedReportCount는 "그 점검들에 연결된 Report 중 FINALIZED 건수"라 participatedCount와 합이
 * 맞지 않는 것이 정상이다(하나의 점검에 보고서가 여러 버전 존재할 수 있고, ANALYZED 상태는 검수
 * 대기라 reviewConfirmedCount/inProgressCount 어느 쪽에도 포함되지 않는다).
 */
public record MyInspectionsSummaryResponse(
        long participatedCount,
        long reviewConfirmedCount,
        long issuedReportCount,
        long inProgressCount) {
}
