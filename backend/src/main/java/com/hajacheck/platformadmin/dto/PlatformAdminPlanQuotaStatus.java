package com.hajacheck.platformadmin.dto;

/**
 * GET /api/platform-admin/plans-quota 표의 "상태" 배지(#624, frontend PlanQuotaUserStatus 대응).
 * 프론트는 이 값을 그대로 배지로 렌더링한다(자체 임계값 재계산 없음).
 */
public enum PlatformAdminPlanQuotaStatus {
    ACTIVE,
    WARNING,
    EXPIRED
}
