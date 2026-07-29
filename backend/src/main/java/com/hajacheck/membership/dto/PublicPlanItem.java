package com.hajacheck.membership.dto;

import com.hajacheck.membership.entity.Plan;
import java.math.BigDecimal;

/**
 * GET /api/plans 응답 항목 — 공개 랜딩페이지 및 요금제 카탈로그 항목.
 */
public record PublicPlanItem(
        Long id,
        String name,
        Integer maxFacilities,
        Integer maxMonthlyAnalyses,
        Integer maxSeats,
        boolean hasPdfWatermark,
        boolean hasCounselorAccess,
        boolean hasAiAddon,
        BigDecimal priceMonthly) {

    public static PublicPlanItem from(Plan plan) {
        return new PublicPlanItem(
                plan.getId(),
                plan.getName().name(),
                plan.getMaxFacilities(),
                plan.getMaxMonthlyAnalyses(),
                plan.getMaxSeats(),
                plan.isHasPdfWatermark(),
                plan.isHasCounselorAccess(),
                plan.isHasAiAddon(),
                plan.getPriceMonthly());
    }
}
