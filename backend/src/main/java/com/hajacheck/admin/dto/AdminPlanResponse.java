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
 *
 * @param currentPeriodEnd 현재 결제 주기 종료 시각(#1104). NULL = 무기한(FREE). 예약 하향의 적용 예정일이
 *                         곧 이 값이라, 화면이 "언제부터 적용되는지"를 별도 조회 없이 보여줄 수 있게 함께 준다.
 * @param scheduledChange  대기 중인 하향 예약(#1105). 없으면 {@code null} — 프론트는 이 필드의 존재로
 *                         "예약 있음/없음"을 판정한다.
 * @param paymentPendingUntil <b>미결제 유예 마감</b>(#1177). 유료→유료 하향이 적용되면 대상 요금제가
 *                         결제 없이 발급되고, 이 시각까지 결제하지 않으면 FREE 로 강등된다. 유예 중이
 *                         아니면 {@code null}.
 *                         <p>⚠️ 유예 중에는 {@code plan.name} 이 유료인데 {@code plan} 의 한도·기능은
 *                         <b>FREE 값</b>이다({@link AdminPlanItem#of}). 이 필드 없이는 <b>하향을 신청한
 *                         바로 그 화면</b>인 관리자 콘솔이 "왜 좌석이 1로 줄고 구성원이 정지됐는지"를
 *                         설명할 수 없다 — 결제 유도 배너의 근거값이다({@code MyPlanResponse} 와 동일 규칙).
 */
public record AdminPlanResponse(
        Long subscriptionId,
        AdminPlanItem plan,
        String status,
        Instant startedAt,
        Instant currentPeriodEnd,
        Usage usage,
        AdminScheduledPlanChangeResponse scheduledChange,
        Instant paymentPendingUntil) {

    public record Usage(
            int analyzedImageCount,
            int analysisRequestCount,
            int facilityCount,
            int seatCount,
            LocalDate period) {
    }

    /**
     * @param effectivePlan 실제 적용 중인 요금제(#1177) — 유예 중이면 FREE, 아니면 {@code plan} 과 같다.
     *                      한도·기능은 반드시 이쪽을 봐야 화면 숫자와 서버의 실제 차단 기준이 일치한다.
     * @param measuredSeatCount <b>좌석 실측값</b>(#1473 / HAJA-652) — {@code QuotaService#measureSeats}
     *                          단일 소스. {@code usage_counters.seat_count}(저장 카운터)는 좌석 점유 시점에
     *                          갱신되지 않아 표시에 그대로 쓰면 드리프트가 난다. {@code MyPlanResponse} 와
     *                          동일 규칙 — {@code usage} 존재 여부와 무관하게 항상 이 값을 쓴다.
     */
    public static AdminPlanResponse from(UserPlan userPlan, Plan plan, UsageCounter usage, LocalDate period,
            AdminScheduledPlanChangeResponse scheduledChange, Plan effectivePlan, int measuredSeatCount) {
        Usage usageInfo = usage == null
                ? new Usage(0, 0, 0, measuredSeatCount, period)
                : new Usage(
                        usage.getAnalyzedImageCount(),
                        usage.getAnalysisRequestCount(),
                        usage.getFacilityCount(),
                        measuredSeatCount,
                        period);
        return new AdminPlanResponse(
                userPlan.getId(),
                AdminPlanItem.of(plan, effectivePlan),
                userPlan.getStatus().name(),
                userPlan.getStartedAt(),
                userPlan.getCurrentPeriodEnd(),
                usageInfo,
                scheduledChange,
                userPlan.getPaymentPendingUntil());
    }
}
