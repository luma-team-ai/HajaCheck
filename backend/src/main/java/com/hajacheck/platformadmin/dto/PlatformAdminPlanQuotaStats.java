package com.hajacheck.platformadmin.dto;

/**
 * GET /api/platform-admin/plans-quota 의 KPI 카드 값 — 검색어(keyword)와 무관하게 전사 기준으로
 * 집계한다(#624, AdminPlanQuotaStats#507 와 동일 계약). totalQuotaUsagePercent 는 회사마다 플랜(=한도)이
 * 다를 수 있어(단일 회사 스코프였던 #507 과 달리) 유효 한도를 가진 사용자별 사용률의 평균이다
 * (frontend "평균 쿼터 사용률" 라벨과 정합).
 */
public record PlatformAdminPlanQuotaStats(
        long activeUsers,
        int totalQuotaUsagePercent) {
}
