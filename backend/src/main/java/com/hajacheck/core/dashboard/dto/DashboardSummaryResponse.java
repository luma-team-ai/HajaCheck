package com.hajacheck.core.dashboard.dto;

/**
 * GET /api/dashboard/summary 응답 — frontend/src/features/dashboard/types.ts DashboardSummary 와 1:1.
 *
 * <p>changeRate 계산 기준(스냅샷 테이블이 없어 아래 근사치로 계산 — 후속 계약 확정 시 조정):
 * <ul>
 *   <li>totalFacilitiesChangeRate: 누적 시설물 수 기준 — (현재 총합 - 이번 달 시작 시점 총합) / 이번 달 시작 시점 총합 * 100</li>
 *   <li>monthlyAnalyzedChangeRate/pendingReviewChangeRate: 점검(inspection_date) 기준 이번 달 vs 지난 달</li>
 *   <li>pendingActionChangeRate: 하자(defects.created_at) 기준 이번 달 vs 지난 달</li>
 * </ul>
 * 분모가 0이면 분자가 0보다 클 때만 100%로, 그 외엔 0%로 처리한다(0으로 나누기 방지).
 */
public record DashboardSummaryResponse(
        long totalFacilities,
        double totalFacilitiesChangeRate,
        long monthlyAnalyzed,
        double monthlyAnalyzedChangeRate,
        long pendingReview,
        double pendingReviewChangeRate,
        long pendingAction,
        double pendingActionChangeRate
) {

    public static double changeRate(long current, long previous) {
        if (previous == 0) {
            return current > 0 ? 100.0 : 0.0;
        }
        return Math.round((current - previous) * 1000.0 / previous) / 10.0;
    }
}
