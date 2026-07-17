package com.hajacheck.membership.dto;

import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.UsageCounter;
import com.hajacheck.membership.entity.UserPlan;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * GET /api/me/plan 응답 — 계약(contract.md "마이페이지" v1) 그대로.
 * limits.* 는 plans.max_* 가 null(무제한)이면 null 을 그대로 반환한다.
 */
public record MyPlanResponse(PlanInfo plan, Limits limits, Usage usage) {

    public record PlanInfo(String name, BigDecimal priceMonthly, String status) {
    }

    public record Limits(Integer maxFacilities, Integer maxMonthlyAnalyses, Integer maxSeats) {
    }

    public record Usage(int facilityCount, int analyzedImageCount, int seatCount, LocalDate period) {
    }

    public static MyPlanResponse from(UserPlan userPlan, Plan plan, UsageCounter usage, LocalDate period) {
        PlanInfo planInfo = new PlanInfo(
                plan.getName().name(),
                plan.getPriceMonthly(),
                userPlan.getStatus().name());
        Limits limits = new Limits(plan.getMaxFacilities(), plan.getMaxMonthlyAnalyses(), plan.getMaxSeats());
        Usage usageInfo = usage == null
                ? new Usage(0, 0, 0, period)
                : new Usage(usage.getFacilityCount(), usage.getAnalyzedImageCount(), usage.getSeatCount(), period);
        return new MyPlanResponse(planInfo, limits, usageInfo);
    }
}
