package com.hajacheck.admin.dto;

/**
 * 사용자 관리 통계 카드 — 검색/필터 조건과 무관하게 전체 사용자 기준으로 집계한다(계약).
 */
public record AdminUserStatsResponse(
        long totalMembers,
        long active,
        long suspended,
        long newThisWeek,
        double newThisWeekGrowthRate) {
}
