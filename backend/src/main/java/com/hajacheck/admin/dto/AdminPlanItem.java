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
        return of(plan, plan);
    }

    /**
     * 구독 요금제와 <b>실제 적용 요금제</b>가 다를 수 있는 경우(#1177 미결제 유예) 전용.
     *
     * <p>정체성({@code id}·{@code name}·{@code priceMonthly})은 <b>구독한</b> 요금제에서, 엔타이틀먼트
     * (한도 3종 + 워터마크·상담사 연결·AI 부가기능)는 <b>실제 적용되는</b> 요금제에서 가져온다. 유예 중에는
     * 후자가 FREE 라({@code PaymentGraceService#resolveEffectivePlan}) 화면이 "STANDARD 인데 좌석 1"을
     * 보여주게 되는데, 그것이 정확히 서버가 강제하는 상태다 — 구독 요금제 기준으로 내보내면 owner 는
     * 좌석이 정지된 화면에서 "좌석 5"를 보게 되고, 무엇이 왜 막혔는지 알 수 없다.
     *
     * @param subscribed 구독한 요금제(정체성·가격)
     * @param effective  실제 적용 중인 요금제(엔타이틀먼트). 유예가 아니면 {@code subscribed} 와 같다.
     */
    public static AdminPlanItem of(Plan subscribed, Plan effective) {
        Plan entitlement = effective == null ? subscribed : effective;
        return new AdminPlanItem(
                subscribed.getId(),
                subscribed.getName().name(),
                entitlement.getMaxFacilities(),
                entitlement.getMaxMonthlyAnalyses(),
                entitlement.getMaxSeats(),
                entitlement.isHasPdfWatermark(),
                entitlement.isHasCounselorAccess(),
                entitlement.isHasAiAddon(),
                subscribed.getPriceMonthly());
    }
}
