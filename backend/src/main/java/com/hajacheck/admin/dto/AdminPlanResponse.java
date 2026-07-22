package com.hajacheck.admin.dto;

import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.UsageCounter;
import com.hajacheck.membership.entity.UserPlan;
import java.time.Instant;
import java.time.LocalDate;

/**
 * GET /api/admin/plan · PATCH /api/admin/plan 응답 — 회사 귀속 현재 구독의 요금제·한도·이번 달 사용량(#507).
 *
 * <p>usage 는 usage_counters 를 <b>읽기만</b> 한다(집계 증가는 QuotaInterceptor 의 원자적 조건부 UPDATE 책임 —
 * table_design.md §usage_counters). 당월 집계 행이 없으면(플랜 변경 직후 등) 0 으로 반환한다.
 */
public record AdminPlanResponse(
        Long subscriptionId,
        AdminPlanItem plan,
        String status,
        Instant startedAt,
        Usage usage) {

    public record Usage(
            int analyzedImageCount,
            int analysisRequestCount,
            int facilityCount,
            int seatCount,
            LocalDate period) {
    }

    public static AdminPlanResponse from(UserPlan userPlan, Plan plan, UsageCounter usage, LocalDate period) {
        Usage usageInfo = usage == null
                ? new Usage(0, 0, 0, 0, period)
                : new Usage(
                        usage.getAnalyzedImageCount(),
                        usage.getAnalysisRequestCount(),
                        usage.getFacilityCount(),
                        usage.getSeatCount(),
                        period);
        return new AdminPlanResponse(
                userPlan.getId(),
                AdminPlanItem.from(plan),
                userPlan.getStatus().name(),
                userPlan.getStartedAt(),
                usageInfo);
    }
}
