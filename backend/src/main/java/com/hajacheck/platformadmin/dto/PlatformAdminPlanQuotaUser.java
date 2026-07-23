package com.hajacheck.platformadmin.dto;

import com.hajacheck.membership.entity.PlanName;

/**
 * GET /api/platform-admin/plans-quota 표의 한 행 — 전사 사용자 1명의 소속 회사 플랜과 이번 달 쿼터
 * 사용 현황(#624, frontend PlanQuotaUser 대응). plan/quotaLimit/remainingDays/companyName 은 사용자가
 * 소속된 회사의 활성 구독(user_plans, company 귀속)에서 나온다 — 회사 미소속(개인 계정)이거나 회사에
 * 활성 구독이 없으면 plan/quotaLimit/remainingDays 는 전부 null 이고 status 는 EXPIRED.
 */
public record PlatformAdminPlanQuotaUser(
        Long id,
        String name,
        String email,
        Long companyId,
        String companyName,
        PlanName plan,
        int quotaUsed,
        Integer quotaLimit,
        Long remainingDays,
        PlatformAdminPlanQuotaStatus status) {
}
