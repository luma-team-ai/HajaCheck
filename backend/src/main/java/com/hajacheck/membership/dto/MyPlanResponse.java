package com.hajacheck.membership.dto;

import com.hajacheck.auth.entity.BusinessVerificationStatus;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.UsageCounter;
import com.hajacheck.membership.entity.UserPlan;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * GET /api/me/plan 응답 — 계약(contract.md "마이페이지" v1) 그대로.
 * limits.* 는 plans.max_* 가 null(무제한)이면 null 을 그대로 반환한다.
 */
public record MyPlanResponse(PlanInfo plan, Limits limits, Usage usage) {

    /**
     * @param paymentPendingUntil <b>미결제 유예 마감</b>(#1177). 유료→유료 하향 예약이 적용되면 대상
     *                            요금제가 결제 없이 발급되고, 이 시각까지 결제하지 않으면 FREE 로
     *                            강등된다. 유예 중이 아니면 {@code null} — 값이 있으면 화면은 "결제 필요"
     *                            배너를 띄우고 결제 경로로 유도해야 한다.
     *                            <p>⚠️ 유예 중에는 {@code name} 이 유료 요금제인데 {@link Limits} 는
     *                            <b>FREE 한도</b>다(무결제 유료 혜택 차단 —
     *                            {@code PaymentGraceService#resolveEffectivePlan}). 이 필드가 없으면
     *                            화면은 "STANDARD 인데 좌석 한도가 1"이라는 모순을 설명할 수 없다.
     */
    public record PlanInfo(
            String name,
            BigDecimal priceMonthly,
            String status,
            LocalDate nextBillingDate,
            Boolean businessVerified,
            Instant paymentPendingUntil) {
    }

    public record Limits(Integer maxFacilities, Integer maxMonthlyAnalyses, Integer maxSeats) {
    }

    public record Usage(int facilityCount, int analyzedImageCount, int seatCount, LocalDate period) {
    }

    /**
     * @param company 회사 구독(companyId != null)이면 그 회사(조회 실패 시 null 가능), 개인 구독이면 null.
     * @param zoneId  nextBillingDate 표시에 쓸 서버 KST 존(호출부의 currentPeriod()와 동일 존이어야 한다).
     * @param effectivePlan <b>실제로 적용 중인 한도</b>의 요금제(#1177) — 평소에는 {@code plan} 과 같은
     *                      객체지만, 미결제 유예 중에는 FREE 다
     *                      ({@code PaymentGraceService#resolveEffectivePlan}). {@link Limits} 는 반드시
     *                      이쪽을 봐야 한다 — {@code plan} 기준으로 내보내면 화면은 "5석"이라고 하는데
     *                      실제 초대는 1석에서 막히는(=한도 강제가 그렇게 동작하는) 불일치가 생긴다.
     *                      요금제 이름·가격은 여전히 {@code plan}(구독한 요금제)이다.
     * @param paymentPendingUntil 미결제 유예 마감(유예 중이 아니면 null) — {@link PlanInfo} javadoc 참고.
     */
    public static MyPlanResponse from(UserPlan userPlan, Plan plan, UsageCounter usage, LocalDate period,
                                       Company company, ZoneId zoneId, Plan effectivePlan,
                                       Instant paymentPendingUntil) {
        // startedAt + 1개월 파생 계산을 실체화된 컬럼으로 교체한다(#1104) — NULL(FREE, 무기한)이면
        // nextBillingDate 도 null. plan.priceMonthly 조건은 더 이상 필요 없다(currentPeriodEnd 자체가
        // 유료 플랜만 채워지므로).
        LocalDate nextBillingDate = userPlan.getCurrentPeriodEnd() == null
                ? null
                : ZonedDateTime.ofInstant(userPlan.getCurrentPeriodEnd(), zoneId).toLocalDate();
        // "회사 구독인지"는 company 조회 성공 여부가 아니라 userPlan.companyId(owner XOR 의 실제 소유 구분)로
        // 판별한다 — 정상 데이터에선 발생 불가하지만, 회사 구독인데 company 조회가 비어 company==null 이 되는
        // 방어적 케이스에서도 businessVerified 는 "미인증"(false)이어야 한다(개인 구독의 null 과 계약상 구분).
        Boolean businessVerified = userPlan.getCompanyId() == null
                ? null
                : company != null && company.getVerificationStatus() == BusinessVerificationStatus.VERIFIED;
        PlanInfo planInfo = new PlanInfo(
                plan.getName().name(),
                plan.getPriceMonthly(),
                userPlan.getStatus().name(),
                nextBillingDate,
                businessVerified,
                paymentPendingUntil);
        // ⚠️ 한도는 effectivePlan 기준이다(#1177) — 유예 중에는 구독 요금제가 아니라 FREE 한도가 실제로
        // 강제되므로, plan 기준으로 내보내면 화면 숫자와 QuotaService 의 차단 기준이 어긋난다.
        Plan limitPlan = effectivePlan == null ? plan : effectivePlan;
        Limits limits = new Limits(
                limitPlan.getMaxFacilities(), limitPlan.getMaxMonthlyAnalyses(), limitPlan.getMaxSeats());
        Usage usageInfo = usage == null
                ? new Usage(0, 0, 0, period)
                : new Usage(usage.getFacilityCount(), usage.getAnalyzedImageCount(), usage.getSeatCount(), period);
        return new MyPlanResponse(planInfo, limits, usageInfo);
    }
}
