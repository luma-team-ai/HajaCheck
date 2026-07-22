package com.hajacheck.admin.dto;

/**
 * GET /api/admin/plan-quota 의 KPI 카드 · "현재 플랜" 카드 값 — 검색어(keyword)와 무관하게 회사 전체
 * 기준으로 집계한다(계약, PlanQuotaPage.tsx 주석 참고).
 */
public record AdminPlanQuotaStats(
        long activeUsers,
        int totalQuotaUsagePercent,
        String companyPlan) {
}
