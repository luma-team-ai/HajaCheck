package com.hajacheck.admin.dto;

import com.hajacheck.membership.entity.Plan;
import java.util.List;

/**
 * GET /api/admin/plans 응답 — 제공 중인 전체 요금제 목록(관리자 플랜 변경 선택지, #507).
 */
public record AdminPlanCatalogResponse(List<AdminPlanItem> plans) {

    public static AdminPlanCatalogResponse from(List<Plan> plans) {
        return new AdminPlanCatalogResponse(plans.stream().map(AdminPlanItem::from).toList());
    }
}
