package com.hajacheck.membership.service;

import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.config.ScheduledPlanChangeProperties;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.ScheduledPlanChangeRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import com.hajacheck.payment.entity.PaymentStatus;
import com.hajacheck.payment.repository.PaymentRepository;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>미결제 유예</b>(#1177 — 유료→유료 하향 C안 "유예 후 강등") 판정의 <b>단일 진실 소스</b>.
 *
 * <h2>왜 이 상태가 존재하는가</h2>
 * {@code ENTERPRISE → STANDARD} 같은 유료→유료 하향은 적용 시점이 <b>무인 배치</b>라 그 순간 결제창을
 * 띄울 수 없다. 그렇다고 새 유료 주기를 그냥 열면 <b>어떤 경로로도 청구되지 않는 유료 한 달</b>이
 * 발급되어 결제 경로 우회가 된다(#1105 보안 리뷰 P1). C안은 그 사이를 "미결제 유예"로 메운다 —
 * 대상 요금제를 발급하되 <b>한도는 FREE 로 낮추고</b>, 유예 안에 결제하면 정상 주기를 시작하고
 * 넘기면 FREE 로 강등한다. 결국 <b>무결제로 얻는 유료 혜택은 0</b> 이다.
 *
 * <h2>표식 컬럼을 두지 않고 파생 판정하는 이유</h2>
 * 스키마 변경 없이(Flyway 마이그레이션 0건) 기존 컬럼·테이블만으로 같은 상태를 표현할 수 있기 때문이다.
 * 판정은 <b>세 조건의 논리곱</b>이다:
 * <ol>
 *   <li><b>유료 요금제</b>({@code plans.price_monthly > 0}) — 무료면 유예 개념 자체가 없다. 이 조건이
 *       먼저 걸러지므로 FREE 사용자(대다수)에게는 <b>추가 조회가 한 건도 발생하지 않는다</b>.</li>
 *   <li><b>예약 실행이 발급한 구독</b> — {@code scheduled_plan_changes} 의 APPLIED 이력과
 *       {@code applied_at == current_period_start} 로 잇는다(조인 축의 근거는
 *       {@link ScheduledPlanChangeRepository#existsAppliedOriginForCompany} javadoc).</li>
 *   <li><b>연결된 PAID 결제가 없다</b> — {@link PaymentRepository#sumAmountByUserPlanIdAndStatus}
 *       (#1146 이 이미 도입한 집계)가 0/NULL.</li>
 * </ol>
 *
 * <p><b>⚠️ 2번을 빼면 안 된다(정상 고객 차단 사고)</b>: "PAID 결제가 없는 유료 구독" 에는 <b>레거시
 * 무결제 유료 구독</b>이 섞여 있다 — 모의 결제({@code MembershipService#checkout}, #711) 시절이나
 * {@code payments} 도입(V20) 이전에 만들어진 구독들이고, 실재한다(#1146 보안 리뷰 P1-A 가 크레딧 상한을
 * 도입한 근거가 바로 그 존재였다). 2번 없이 판정하면 그 고객들이 <b>유예 만료로 오인돼 FREE 로
 * 강등</b>된다. 반대로 2번만으로도 부족하다 — 결제로 해소된 뒤에도 예약 이력은 남으므로 3번이 필요하다.
 *
 * <h2>결제로 해소되는 방식</h2>
 * 결제가 승인되면 {@code PaymentWriter#applyPlanTransition} 이 {@code PlanTransitionService#transitionTo}
 * 로 <b>새 구독 행</b>을 발급하고({@code startNewBillingPeriod} = 정상 1개월 주기) 유예 행을 EXPIRED 로
 * 내린다. 새 행은 {@code current_period_start} 가 배치 {@code now} 와 무관하므로 2번이 거짓이 되고,
 * {@code payments.user_plan_id} 로 PAID 가 연결되므로 3번도 거짓이 된다 — <b>표식 해제 코드가 따로
 * 필요 없다</b>.
 *
 * <h2>성능</h2>
 * 한도 판정({@code QuotaService})은 요청마다 타는 경로다. 그래서 순서를 <b>싼 것 → 비싼 것</b>으로
 * 고정한다: (a) 요금제 가격·{@code current_period_start} 널 검사는 이미 로딩된 엔티티만 본다(조회 0건),
 * (b) 예약 이력 조회는 사람이 만든 예약만 담긴 <b>작은 테이블</b>이라 거의 항상 여기서 false 로 끝나고,
 * (c) 결제 집계는 그 뒤에야 실행된다. 즉 <b>정상 유료 구독의 추가 비용은 작은 테이블 조회 1건</b>이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentGraceService {

    private final PlanRepository planRepository;
    private final UserPlanRepository userPlanRepository;
    private final PaymentRepository paymentRepository;
    private final ScheduledPlanChangeRepository scheduledPlanChangeRepository;
    private final ScheduledPlanChangeProperties properties;

    /**
     * 이 구독이 지금 <b>미결제 유예</b> 상태인가(클래스 javadoc 의 세 조건 논리곱).
     *
     * <p>⚠️ "유예 <b>기간이 남아 있는가</b>" 는 보지 않는다 — 유예가 만료돼도 강등 배치가 실행되기
     * 전까지는 여전히 미결제 상태이고, 그 사이에 유료 한도를 주면 안 되기 때문이다(배치가 매시라 최대
     * 1시간의 창이 있다). 만료 여부는 강등 배치가 {@code current_period_end} 로 따로 판정한다.
     *
     * @param plan {@code userPlan.planId} 에 해당하는 요금제. 호출부가 이미 로딩한 것을 넘겨 중복 조회를
     *             막는다(가격만 보므로 지연 로딩 프록시여도 무방하다).
     */
    public boolean isInGracePeriod(UserPlan userPlan, Plan plan) {
        if (userPlan == null || plan == null) {
            return false;
        }
        // (a) 무료 요금제 — 유예 개념이 없다. FREE 사용자는 여기서 끝나므로 추가 조회가 0건이다.
        if (priceOrZero(plan).signum() <= 0) {
            return false;
        }
        Instant periodStart = userPlan.getCurrentPeriodStart();
        if (periodStart == null) {
            // 예약 실행은 반드시 current_period_start 를 배치 now 로 채운다(UserPlan#startPaymentGracePeriod).
            // 비어 있으면 그 경로로 발급된 구독이 아니다.
            return false;
        }
        // (b) 예약 실행이 발급했다는 증거 — 작은 테이블이라 싸고, 거의 항상 여기서 끝난다.
        if (!hasScheduledOrigin(userPlan, periodStart)) {
            return false;
        }
        // (c) 아직 정산되지 않았는가. (b)를 통과한 소수의 구독에서만 실행된다.
        return !hasSettlementPayment(userPlan.getId());
    }

    /**
     * id 로 유예 여부를 확인한다 — 유예 만료 강등 배치({@code ScheduledPlanChangeScheduler} 2단계)의
     * <b>최종 확인</b> 전용이다.
     *
     * <p>후보 조회는 성능을 위해 "예약 실행이 발급했고 주기가 끝난 유료 구독"까지만 좁히므로(결제 유무는
     * 다른 모듈 테이블이라 조회에 섞지 않는다), 강등 <b>직전에</b> 여기서 정산 여부까지 포함해 다시
     * 판정한다. 구독 행이 사라졌으면 {@code false}(강등하지 않는다 — 배치를 죽이지도 않는다).
     */
    public boolean isInGracePeriodById(Long userPlanId) {
        if (userPlanId == null) {
            return false;
        }
        return userPlanRepository.findById(userPlanId)
                .map(userPlan -> planRepository.findById(userPlan.getPlanId())
                        .map(plan -> isInGracePeriod(userPlan, plan))
                        .orElse(false))
                .orElse(false);
    }

    /**
     * 한도 판정에 실제로 적용할 요금제 — <b>유예 중이면 FREE</b>, 아니면 구독 요금제 그대로.
     *
     * <p><b>왜 FREE 인가</b>(#1177 결정표): 유예 중에 대상 요금제(STANDARD 등)의 한도를 주면 예약을
     * 반복하는 것만으로 유예기간만큼 상위 한도를 무료로 쓸 수 있다 — #1105 보안 P1(무결제 유료 발급)과
     * 같은 부류의 우회로다. 좌석 정지 기준도 같다({@code ScheduledPlanChangeWriter} 가 유예 진입 시점에
     * FREE 기준으로 초과 좌석을 정리한다) — 한도와 정지 기준이 갈라지면 "정지는 안 됐는데 아무것도 못
     * 하는" 인원이 생긴다.
     *
     * <p>⚠️ 이 트레이드오프는 의도된 것이다: 유예 진입 시점에 좌석이 FREE 기준으로 정리되므로 체감은
     * 즉시 강등에 가깝다. 그럼에도 이 방향을 택한 건 무결제 유료 혜택 구멍을 만들지 않기 위해서다.
     */
    public Plan resolveEffectivePlan(UserPlan userPlan, Plan plan) {
        return isInGracePeriod(userPlan, plan) ? freePlan() : plan;
    }

    /**
     * 이 구독의 <b>결제 마감 시각</b>(유예 종료일). 유예 중이 아니면 {@code null}.
     *
     * <p>값은 {@code current_period_end} 다 — 발급 시점에
     * {@code current_period_start + payment-grace-days}(KST) 로 쓰인 바로 그 값이며
     * ({@code UserPlan#startPaymentGracePeriod}), 강등 배치가 실제로 <b>강제하는 기준</b>도 이 컬럼이다.
     * 설정값으로 매번 다시 계산하지 않는 이유: 운영 중 {@code payment-grace-days} 를 바꾸면 화면·알림이
     * 말하는 마감과 배치가 강제하는 마감이 갈라지고, 진행 중인 유예가 소급해 늘거나 줄어 보인다.
     */
    public Instant resolveGraceDeadline(UserPlan userPlan, Plan plan) {
        return isInGracePeriod(userPlan, plan) ? userPlan.getCurrentPeriodEnd() : null;
    }

    /** 유예 발급 시 열어 줄 일수(설정값) — {@code UserPlan#startPaymentGracePeriod} 호출부 전용. */
    public int graceDays() {
        return properties.getPaymentGraceDays();
    }

    /**
     * 유예 중 한도로 적용할 FREE 요금제. 시드가 없으면 설정 오류이므로 fail-fast 한다
     * ({@code PlanProvisioningService}·{@code PlanExpiryWriter} 와 같은 판단).
     */
    public Plan freePlan() {
        return planRepository.findByName(PlanName.FREE)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_DATA_INVALID));
    }

    private boolean hasScheduledOrigin(UserPlan userPlan, Instant periodStart) {
        Long companyId = userPlan.getCompanyId();
        if (companyId != null) {
            return scheduledPlanChangeRepository.existsAppliedOriginForCompany(
                    companyId, userPlan.getPlanId(), periodStart);
        }
        Long userId = userPlan.getUserId();
        return userId != null && scheduledPlanChangeRepository.existsAppliedOriginForUser(
                userId, userPlan.getPlanId(), periodStart);
    }

    /**
     * 이 구독에 실제로 정산된 결제가 있는가 — #1146 이 크레딧 상한용으로 도입한 집계를 그대로 쓴다.
     * 새 조회를 추가하지 않는 이유는 두 판정("실제로 낸 돈이 있는가")이 정확히 같은 질문이기 때문이다.
     */
    private boolean hasSettlementPayment(Long userPlanId) {
        BigDecimal paid = paymentRepository.sumAmountByUserPlanIdAndStatus(userPlanId, PaymentStatus.PAID);
        return paid != null && paid.signum() > 0;
    }

    private BigDecimal priceOrZero(Plan plan) {
        return plan.getPriceMonthly() == null ? BigDecimal.ZERO : plan.getPriceMonthly();
    }
}
