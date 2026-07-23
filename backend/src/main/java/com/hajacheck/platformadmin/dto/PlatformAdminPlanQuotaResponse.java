package com.hajacheck.platformadmin.dto;

import java.util.List;

/**
 * GET /api/platform-admin/plans-quota 응답 — 전사 사용자별 플랜·쿼터 사용 현황 목록(#624,
 * frontend PlanQuotaListResponse 대응). page 는 1-base(frontend 계약과 정합).
 */
public record PlatformAdminPlanQuotaResponse(
        List<PlatformAdminPlanQuotaUser> content,
        int page,
        int size,
        long totalElements,
        PlatformAdminPlanQuotaStats stats) {
}
