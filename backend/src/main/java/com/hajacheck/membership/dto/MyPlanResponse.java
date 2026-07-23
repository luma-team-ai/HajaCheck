package com.hajacheck.membership.dto;

import com.hajacheck.auth.entity.BusinessVerificationStatus;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.UsageCounter;
import com.hajacheck.membership.entity.UserPlan;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * GET /api/me/plan 응답 — 계약(contract.md "마이페이지" v1) 그대로.
 * limits.* 는 plans.max_* 가 null(무제한)이면 null 을 그대로 반환한다.
 */
public record MyPlanResponse(PlanInfo plan, Limits limits, Usage usage) {

    public record PlanInfo(
            String name,
            BigDecimal priceMonthly,
            String status,
            LocalDate nextBillingDate,
            Boolean businessVerified) {
    }

    public record Limits(Integer maxFacilities, Integer maxMonthlyAnalyses, Integer maxSeats) {
    }

    public record Usage(int facilityCount, int analyzedImageCount, int seatCount, LocalDate period) {
    }

    /**
     * @param company 회사 구독(companyId != null)이면 그 회사, 개인 구독이면 null.
     * @param zoneId  nextBillingDate 계산에 쓸 서버 KST 존(호출부의 currentPeriod()와 동일 존이어야 한다).
     */
    public static MyPlanResponse from(UserPlan userPlan, Plan plan, UsageCounter usage, LocalDate period,
                                       Company company, ZoneId zoneId) {
        LocalDate nextBillingDate = plan.getPriceMonthly() != null && plan.getPriceMonthly().signum() > 0
                ? ZonedDateTime.ofInstant(userPlan.getStartedAt(), zoneId).toLocalDate().plusMonths(1)
                : null;
        Boolean businessVerified = company == null
                ? null
                : company.getVerificationStatus() == BusinessVerificationStatus.VERIFIED;
        PlanInfo planInfo = new PlanInfo(
                plan.getName().name(),
                plan.getPriceMonthly(),
                userPlan.getStatus().name(),
                nextBillingDate,
                businessVerified);
        Limits limits = new Limits(plan.getMaxFacilities(), plan.getMaxMonthlyAnalyses(), plan.getMaxSeats());
        Usage usageInfo = usage == null
                ? new Usage(0, 0, 0, period)
                : new Usage(usage.getFacilityCount(), usage.getAnalyzedImageCount(), usage.getSeatCount(), period);
        return new MyPlanResponse(planInfo, limits, usageInfo);
    }
}
