package com.hajacheck.admin.dto;

/**
 * GET /api/admin/plan-quota 표의 한 행 — 회사 소속 멤버(개인 계정) 1명의 이번 달 쿼터 사용 현황(#507).
 *
 * <p>plan/quotaLimit 은 회사가 구독한 단일 플랜에서 나오는 값이라 모든 행에서 동일하다(company_memberships
 * 상속). 활성 구독이 없으면 두 값 모두 null. quotaUsed 만 멤버별로 다르다.
 */
public record AdminPlanQuotaMember(
        Long id,
        String name,
        String email,
        String plan,
        int quotaUsed,
        Integer quotaLimit) {
}
