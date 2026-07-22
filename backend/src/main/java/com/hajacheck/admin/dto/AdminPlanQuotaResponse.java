package com.hajacheck.admin.dto;

import java.util.List;

/**
 * GET /api/admin/plan-quota 응답 — 요청 관리자 소속 회사의 활성 멤버별 쿼터 사용 현황 목록(#507,
 * frontend PlanQuotaPage.tsx 대응). page 는 1-base(프론트 계약과 정합).
 */
public record AdminPlanQuotaResponse(
        List<AdminPlanQuotaMember> content,
        int page,
        int size,
        long totalElements,
        AdminPlanQuotaStats stats) {
}
