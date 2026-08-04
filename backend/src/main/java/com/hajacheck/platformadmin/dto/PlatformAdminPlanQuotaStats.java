package com.hajacheck.platformadmin.dto;

/**
 * GET /api/platform-admin/plans-quota 의 KPI 카드 값 — 검색어(keyword)와 무관하게 전사 기준으로
 * 집계한다(#624, AdminPlanQuotaStats#507 와 동일 계약). totalQuotaUsagePercent 는 usage_counters
 * (회사=UserPlan 단위 쿼터 차감의 진짜 원천, #1407)를 회사별 한도로 나눈 사용률의 평균이다 — 회사마다
 * 플랜(=한도)이 다를 수 있어(단일 회사 스코프였던 #507 과 달리) 단일 한도로 나눌 수 없다
 * (frontend "평균 쿼터 사용률" 라벨과 정합).
 *
 * <p>unlimitedPlanUsageTotal(#1407 후속): ENTERPRISE 등 한도가 없는(maxMonthlyAnalyses == null) 플랜은
 * "사용량 ÷ 한도"가 정의되지 않아 totalQuotaUsagePercent 평균에서 항상 제외된다. 그 사용량이 화면에서
 * 통째로 사라지지 않도록, 유효(비만료) 무제한 플랜들의 이번 달 analyzedImageCount 합계를 별도로 내려준다
 * (frontend 는 평균 사용률 카드에 보조 텍스트로 병기).
 */
public record PlatformAdminPlanQuotaStats(
        long activeUsers,
        int totalQuotaUsagePercent,
        long unlimitedPlanUsageTotal) {
}
