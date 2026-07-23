package com.hajacheck.platformadmin.dto;

import com.hajacheck.membership.entity.Plan;
import java.util.List;

/**
 * GET /api/platform-admin/plans 응답 — 전체 요금제 카탈로그(#624 후속, 플랜 정책 설정 화면 초기값).
 */
public record PlatformAdminPlanCatalogResponse(List<PlatformAdminPlanItem> plans) {

    public static PlatformAdminPlanCatalogResponse from(List<Plan> plans) {
        return new PlatformAdminPlanCatalogResponse(plans.stream().map(PlatformAdminPlanItem::from).toList());
    }
}
