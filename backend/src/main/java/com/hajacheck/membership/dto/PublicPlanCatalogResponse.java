package com.hajacheck.membership.dto;

import com.hajacheck.membership.entity.Plan;
import java.util.List;

/**
 * GET /api/plans 응답 — 공개 요금제 카탈로그 목록.
 */
public record PublicPlanCatalogResponse(List<PublicPlanItem> plans) {

    public static PublicPlanCatalogResponse from(List<Plan> plans) {
        return new PublicPlanCatalogResponse(plans.stream().map(PublicPlanItem::from).toList());
    }
}
