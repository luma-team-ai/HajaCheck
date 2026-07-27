package com.hajacheck.payment.service;

import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.service.PlanDowngradeService;
import com.hajacheck.membership.service.PlanTransitionService;
import com.hajacheck.payment.config.TossPaymentsProperties;
import com.hajacheck.payment.dto.PaymentConfirmRequest;
import com.hajacheck.payment.dto.PaymentOrderResponse;
import com.hajacheck.payment.entity.Payment;
import com.hajacheck.payment.repository.PaymentRepository;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 흐름의 <b>DB 트랜잭션 전담</b> 빈(#988 / HAJA-489) — 외부 PG 호출을 하는
 * {@link PaymentService} 와 별도 빈으로 나눈다.
 *
 * <p><b>왜 나누는가(트랜잭션 경계 설계)</b>
 * <ol>
 *   <li><b>PG 호출을 트랜잭션 안에 두지 않는다.</b> 승인은 카드사 왕복이 포함돼 초 단위로 늘어질 수 있는데,
 *       그 시간 동안 DB 커넥션과 (잠금을 잡았다면) 행 잠금을 쥐고 있으면 결제 트래픽이 곧 커넥션 풀 고갈이
 *       된다. 그래서 "검증 트랜잭션 → (트랜잭션 밖) PG 호출 → 반영 트랜잭션" 3단계로 쪼갠다.</li>
 *   <li><b>승인 실패 기록이 롤백되면 안 된다.</b> 하나의 트랜잭션이었다면 PAYMENT_GATEWAY_ERROR 를 던지는
 *       순간 방금 쓴 FAILED 기록까지 롤백돼 실패 이력이 사라진다. {@link #markFailed} 를 독립 트랜잭션으로
 *       두어 실패도 반드시 남긴다.</li>
 *   <li><b>승인 기록과 플랜 전이를 분리한다.</b> {@link #markApproved}(돈이 오간 사실)를 먼저 커밋하고
 *       {@link #applyPlanTransition}(엔타이틀먼트)을 뒤이어 커밋한다. 한 트랜잭션이면 전이 단계의 예외
 *       (예: 동시 플랜 변경 경합)가 <b>승인 기록까지 롤백</b>시켜 "돈은 나갔는데 원장에 없음"이 된다.
 *       분리해 두면 최악의 경우가 {@code status=PAID && user_plan_id is null} 이라는 <b>탐지 가능한 상태</b>로
 *       남고, 같은 orderId 재요청이 PG 재호출 없이 전이만 재시도해 스스로 복구된다
 *       ({@link PaymentService#confirm} 의 멱등 분기).</li>
 * </ol>
 *
 * <p>self-invocation 으로 {@code @Transactional} 프록시가 풀리는 것을 막기 위해 별도 빈으로 둔다
 * ({@code PendingBusinessReverifyWriter}·{@code CompanyAccountWriter} 와 동일한 이유).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentWriter {

    /** 주문 식별자 접두사 — 토스 규격(6~64자, 영숫자·'-'·'_')을 만족하고 운영 로그에서 식별하기 쉽게. */
    private static final String ORDER_ID_PREFIX = "haja-";

    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final PlanRepository planRepository;
    private final PlanTransitionService planTransitionService;
    private final PlanDowngradeService planDowngradeService;
    private final TossPaymentsClient tossPaymentsClient;
    private final TossPaymentsProperties tossPaymentsProperties;

    /**
     * 주문 사전 등록(READY) — 결제창을 띄우기 전에 서버가 {@code orderId}·{@code amount} 를 확정한다.
     *
     * <p>모의 결제({@code MembershipService#checkout}, #711)가 갖고 있던 가드를 <b>돈이 움직이기 전인 이
     * 단계로</b> 전부 옮겼다: FREE 거부 · 구독 소유자 인가 · 하향 초과 확인 요구(#890) · 동일 플랜 재결제 차단.
     * 승인 단계에서 처음 거절하면 사용자가 이미 카드 인증을 마친 뒤라 UX 도 나쁘고 환불 처리도 필요해진다.
     */
    @Transactional
    public PaymentOrderResponse createOrder(Long userId, PlanName targetPlanName) {
        requireGatewayConfigured();

        if (targetPlanName == PlanName.FREE) {
            // FREE 로의 하향은 결제 흐름이 아니다(청구할 금액이 없다). 다운그레이드는 관리자 콘솔의
            // "변경 미리보기 → 명시적 확인" 경로(AdminPlanService#changePlan)가 담당한다.
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        User user = findUser(userId);
        Long companyId = user.getCompanyId();
        UserPlan current = planTransitionService.resolveCurrentUserPlan(userId, companyId);
        planTransitionService.requireSubscriptionOwner(userId, companyId, current);

        Plan targetPlan = planRepository.findByName(targetPlanName)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_DATA_INVALID));

        // 이미 그 요금제를 쓰고 있으면 주문을 만들지 않는다. 모의 결제 시절엔 "멱등 no-op(200)" 이었지만,
        // 실결제에서 no-op 주문을 내주면 사용자가 아무것도 바뀌지 않는 데 돈을 낸다 — 청구 전에 거절하는
        // 것이 맞다. 상태 충돌이라 409(PLAN_ACTIVE_SUBSCRIPTION_CONFLICT)로 계약 안에서 표현한다.
        if (current.getStatus() == UserPlanStatus.ACTIVE && current.getPlanId().equals(targetPlan.getId())) {
            throw new BusinessException(ErrorCode.PLAN_ACTIVE_SUBSCRIPTION_CONFLICT);
        }

        requireNoUnconfirmedDowngrade(companyId, current, targetPlan);

        long amount = toChargeableAmount(targetPlan.getPriceMonthly());
        Payment payment = paymentRepository.save(Payment.createOrder(
                ORDER_ID_PREFIX + UUID.randomUUID(),
                userId,
                companyId,
                targetPlan.getId(),
                targetPlanName,
                targetPlan.getPriceMonthly()));

        log.info("결제 주문 생성 — orderId={} planName={} companyId={}",
                payment.getOrderId(), targetPlanName, companyId);
        return PaymentOrderResponse.of(payment, amount, buildOrderName(targetPlanName));
    }

    /**
     * 승인 호출 직전 검증(읽기 전용) — 여기서 통과하지 못하면 <b>PG 를 호출하지 않는다</b>. 금액 대조와
     * 플랜 전이 가드를 모두 이 단계에서 다시 확인해, 돈이 움직인 뒤에 거절하는 상황을 만들지 않는다.
     *
     * <p>금액 불일치는 결제 주문을 FAILED 로 태우지 않는다 — 위변조 시도든 클라이언트 버그든 정상 주문
     * 하나를 못 쓰게 만들 이유가 없고, PG 호출을 막는 것으로 방어는 이미 끝났다(WARN 로깅만).
     */
    @Transactional(readOnly = true)
    public PaymentConfirmPreparation prepareConfirm(Long userId, PaymentConfirmRequest request) {
        requireGatewayConfigured();

        Payment payment = paymentRepository.findByOrderId(request.orderId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));
        if (!payment.isOwnedBy(userId)) {
            // 남의 주문 — 존재 여부를 흘리지 않기 위해 상태·금액 정보는 일절 응답에 담지 않는다.
            throw new BusinessException(ErrorCode.PAYMENT_FORBIDDEN);
        }
        if (payment.isPaid()) {
            // 멱등(보안 요구 3) — 리다이렉트 새로고침·중복 전송. PG 재호출 없이 호출부가 현재 상태를 돌려준다.
            return PaymentConfirmPreparation.alreadyPaid(
                    payment.getId(), payment.isPaidWithoutPlanApplied());
        }
        if (!payment.isConfirmable()) {
            // FAILED·CANCELED 는 재확정 대상이 아니다. 상태를 세분해 알려주지 않는다(ErrorCode javadoc).
            throw new BusinessException(ErrorCode.PAYMENT_ORDER_NOT_FOUND);
        }

        long amount = toChargeableAmount(payment.getAmount());
        if (request.amount() == null || request.amount() != amount) {
            log.warn("결제 승인 금액 불일치 — orderId={} (PG 호출 차단)", payment.getOrderId());
            throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }

        // 승인 후 전이 단계에서 실패할 조건을 미리 걸러낸다(돈이 움직이기 전에).
        resolveTransitionTarget(payment);

        return PaymentConfirmPreparation.readyToApprove(payment.getId(), amount);
    }

    /**
     * PG 승인 성공 반영 — 돈이 오간 사실을 <b>먼저 독립 커밋</b>한다(클래스 javadoc §3). 같은 주문에 대한
     * 동시 승인은 {@code findByIdForUpdate} 잠금으로 직렬화되고, 뒤 트랜잭션은 앞의 커밋 결과(PAID)를 보고
     * {@link Payment#markPaid} 의 멱등 분기로 빠진다.
     */
    @Transactional
    public void markApproved(Long paymentId, TossPaymentApproval approval) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));
        payment.markPaid(approval.paymentKey(), approval.method(), approval.receiptUrl(),
                approval.approvedAt());
        log.info("결제 승인 반영 — orderId={} method={}", payment.getOrderId(), approval.method());
    }

    /**
     * PG 승인 실패 반영 — 독립 트랜잭션이라 호출부가 이어서 PAYMENT_GATEWAY_ERROR 를 던져도 이 기록은
     * 남는다. 이미 PAID 인 행은 건드리지 않는다({@link Payment#markFailed} 가 방어).
     */
    @Transactional
    public void markFailed(Long paymentId, String failureCode, String failureMessage) {
        paymentRepository.findByIdForUpdate(paymentId).ifPresentOrElse(
                payment -> {
                    payment.markFailed(failureCode, failureMessage);
                    log.warn("결제 승인 실패 기록 — orderId={} code={}", payment.getOrderId(), failureCode);
                },
                () -> log.warn("결제 승인 실패 반영 대상 주문 소멸 — paymentId={}", paymentId));
    }

    /**
     * 승인된 결제에 대한 플랜 전이 — <b>승인 성공 후에만</b> 호출된다(보안 요구 5). 멱등이다: 이미
     * {@code user_plan_id} 가 연결됐으면 아무것도 하지 않는다.
     *
     * <p>인가를 <b>이 트랜잭션에서 다시</b> 확인한다. 주문 생성과 승인 사이에 소유자가 바뀌었을 수 있고,
     * 그 사이 남이 결제 콜백을 대신 던지는 경로를 인가 없이 통과시키면 안 된다.
     */
    @Transactional
    public void applyPlanTransition(Long paymentId) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));
        if (!payment.isPaid()) {
            // 승인되지 않은 결제로는 절대 플랜을 주지 않는다(보안 요구 5의 최종 방어선).
            throw new BusinessException(ErrorCode.PAYMENT_GATEWAY_ERROR);
        }
        if (payment.getUserPlanId() != null) {
            return; // 이미 반영됨(동시 재요청·재시도).
        }

        TransitionTarget target = resolveTransitionTarget(payment);
        if (target.alreadyOnTargetPlan()) {
            // 승인과 전이 사이에 다른 경로(관리자 플랜 변경·다른 주문의 승인)로 이미 목표 플랜이 된 경우.
            // 플랜을 다시 갈아끼우면 불필요한 이력 행이 생기고 사용량 이월만 반복되므로 연결만 한다.
            payment.linkUserPlan(target.current().getId());
            return;
        }

        UserPlan renewed = planTransitionService.transitionTo(
                payment.getUserId(), payment.getCompanyId(), target.current(), target.targetPlan());
        payment.linkUserPlan(renewed.getId());
        log.info("결제 승인에 따른 플랜 전이 완료 — orderId={} planName={}",
                payment.getOrderId(), payment.getPlanName());
    }

    /**
     * 전이 대상 확정 + 전이 가드 재검증. {@link #prepareConfirm}(승인 전)과 {@link #applyPlanTransition}
     * (승인 후)이 <b>같은 판정</b>을 쓰도록 한 곳에 모은다 — 두 곳이 갈라지면 "승인 전엔 통과했는데 승인
     * 후에 거절"되는 최악의 조합이 생긴다.
     */
    private TransitionTarget resolveTransitionTarget(Payment payment) {
        User user = findUser(payment.getUserId());
        if (!Objects.equals(user.getCompanyId(), payment.getCompanyId())) {
            // 주문 생성 이후 소속이 바뀐 주문 — 다른 구독에 결제를 붙이면 엉뚱한 회사가 요금제를 받는다.
            throw new BusinessException(ErrorCode.PAYMENT_FORBIDDEN);
        }

        Long companyId = payment.getCompanyId();
        UserPlan current = planTransitionService.resolveCurrentUserPlan(payment.getUserId(), companyId);
        planTransitionService.requireSubscriptionOwner(payment.getUserId(), companyId, current);

        Plan targetPlan = planRepository.findByName(payment.getPlanName())
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_DATA_INVALID));
        if (current.getStatus() == UserPlanStatus.ACTIVE && current.getPlanId().equals(targetPlan.getId())) {
            return new TransitionTarget(current, targetPlan, true);
        }

        requireNoUnconfirmedDowngrade(companyId, current, targetPlan);
        return new TransitionTarget(current, targetPlan, false);
    }

    /**
     * 하향 초과 확인 요구(#890) — 셀프 결제 화면에는 "무엇이 바뀌는지" 확인 단계가 없으므로, 하향으로
     * 한도를 넘게 되면 거절하고 관리자 콘솔의 미리보기 경로로 유도한다. 좌석뿐 아니라 시설물 초과도 함께
     * 본다(시설물 읽기전용은 상태 컬럼이 아니라 계산 판정이라, 플랜 행이 바뀌는 순간 즉시 뒤집힌다).
     */
    private void requireNoUnconfirmedDowngrade(Long companyId, UserPlan current, Plan targetPlan) {
        if (companyId == null) {
            return; // 개인 구독은 좌석·회사 시설물 개념이 없다.
        }
        Plan currentPlan = planRepository.findById(current.getPlanId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_DATA_INVALID));
        if (planDowngradeService.preview(companyId, currentPlan, targetPlan).exists()) {
            throw new BusinessException(ErrorCode.PLAN_DOWNGRADE_CONFIRMATION_REQUIRED);
        }
    }

    private void requireGatewayConfigured() {
        if (!tossPaymentsClient.isConfigured()) {
            // 시크릿 미설정 — 빈 문자열로 PG 인증을 시도하거나 NPE 로 500 이 새게 두지 않고 결제 진입점에서
            // 명확히 fail-close 한다(TossPaymentsProperties javadoc 의 트레이드오프 참고).
            log.error("결제 시크릿 미설정 — 결제 기능 비활성(앱 기동은 막지 않는다)");
            throw new BusinessException(ErrorCode.PAYMENT_GATEWAY_ERROR);
        }
    }

    /**
     * 청구 금액 확정 — 원화는 소수부가 없고 PG 결제창·승인 API 도 정수를 요구한다. 요금제 가격이 0 이하이거나
     * 소수부를 가지면 청구할 수 없는 데이터이므로 조용히 반올림하지 않고 데이터 오류로 표면화한다.
     */
    private long toChargeableAmount(BigDecimal price) {
        if (price == null || price.signum() <= 0) {
            throw new BusinessException(ErrorCode.PLAN_DATA_INVALID);
        }
        try {
            return price.stripTrailingZeros().longValueExact();
        } catch (ArithmeticException e) {
            throw new BusinessException(ErrorCode.PLAN_DATA_INVALID);
        }
    }

    private String buildOrderName(PlanName planName) {
        return tossPaymentsProperties.getOrderNamePrefix() + " " + planName.name() + " 플랜 구독";
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    private record TransitionTarget(UserPlan current, Plan targetPlan, boolean alreadyOnTargetPlan) {
    }
}
