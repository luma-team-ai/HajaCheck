package com.hajacheck.admin.dto;

import com.hajacheck.membership.entity.Plan;
import java.math.BigDecimal;

/**
 * 요금제 카탈로그 항목 — 관리자 플랜 변경 UI 의 선택지(FR-8-A, #507).
 * max_* 는 plans DDL 상 nullable(무제한)이면 null 을 그대로 반환한다(마이페이지 계약과 정합).
 */
public record AdminPlanItem(
        Long id,
        String name,
        Integer maxFacilities,
        Integer maxMonthlyAnalyses,
        Integer maxSeats,
        boolean hasPdfWatermark,
        boolean hasCounselorAccess,
        boolean hasAiAddon,
        BigDecimal priceMonthly) {

    public static AdminPlanItem from(Plan plan) {
        return new AdminPlanItem(
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
