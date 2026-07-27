package com.hajacheck.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.dto.DowngradeOverflow;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.service.PlanDowngradeService;
import com.hajacheck.membership.service.PlanTransitionService;
import com.hajacheck.payment.config.TossPaymentsProperties;
import com.hajacheck.payment.dto.PaymentConfirmRequest;
import com.hajacheck.payment.dto.PaymentOrderResponse;
import com.hajacheck.payment.entity.Payment;
import com.hajacheck.payment.entity.PaymentMethod;
import com.hajacheck.payment.entity.PaymentStatus;
import com.hajacheck.payment.repository.PaymentRepository;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * PaymentWriter 단위테스트(#988 / HAJA-489) — 결제 트랜잭션 단계별 가드.
 *
 * <p>여기서 고정하는 계약(handoff 보안 요구):
 * <ul>
 *   <li>1. 금액은 서버가 정한다 — 주문에 저장되는 금액이 {@code plans.price_monthly} 그대로인가</li>
 *   <li>2. orderId 사전 등록 + 금액 대조 — 불일치면 PG 호출 전에 거절되는가</li>
 *   <li>3. 멱등 — 이미 PAID 인 주문 재요청이 승인 재호출 없이 처리되는가</li>
 *   <li>4. 인가 — 주문 소유자·구독 소유자가 아닌 요청이 막히는가</li>
 *   <li>5. 승인 성공 후에만 전이 — 미승인 결제로 전이가 시도되지 않는가</li>
 * </ul>
 * 그리고 모의 결제(#711 checkout)에서 이관된 가드(FREE 거부·동일 플랜·하향 초과 확인)도 함께 고정한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentWriterTest {

    private static final Long USER_ID = 1L;
    private static final Long COMPANY_ID = 10L;
    private static final Long STANDARD_PLAN_ID = 100L;
    private static final Long ENTERPRISE_PLAN_ID = 101L;
    private static final Long PAYMENT_ID = 900L;
    private static final String ORDER_ID = "haja-00000000-0000-0000-0000-000000000001";

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PlanRepository planRepository;
    @Mock
    private PlanTransitionService planTransitionService;
    @Mock
    private PlanDowngradeService planDowngradeService;
    @Mock
    private TossPaymentsClient tossPaymentsClient;

    private TossPaymentsProperties properties;
    private PaymentWriter writer;

    private User individualUser;
    private User companyUser;
    private Plan standardPlan;
    private Plan enterprisePlan;

    @BeforeEach
    void setUp() {
        properties = new TossPaymentsProperties();
        writer = new PaymentWriter(paymentRepository, userRepository, planRepository,
                planTransitionService, planDowngradeService, tossPaymentsClient, properties);

        individualUser = user(USER_ID, null);
        companyUser = user(USER_ID, COMPANY_ID);
        standardPlan = plan(PlanName.STANDARD, STANDARD_PLAN_ID, new BigDecimal("99000.00"), 3);
        enterprisePlan = plan(PlanName.ENTERPRISE, ENTERPRISE_PLAN_ID, new BigDecimal("299000.00"), null);

        when(tossPaymentsClient.isConfigured()).thenReturn(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser));
        when(planRepository.findByName(PlanName.STANDARD)).thenReturn(Optional.of(standardPlan));
        when(planRepository.findByName(PlanName.ENTERPRISE)).thenReturn(Optional.of(enterprisePlan));
        when(planRepository.findById(STANDARD_PLAN_ID)).thenReturn(Optional.of(standardPlan));
        when(planRepository.findById(ENTERPRISE_PLAN_ID)).thenReturn(Optional.of(enterprisePlan));
        when(planDowngradeService.preview(anyLong(), any(Plan.class), any(Plan.class)))
                .thenReturn(DowngradeOverflow.none());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));
    }

    // ── 주문 사전 등록 ──

    @Test
    void 주문금액은_요청이아니라_요금제가격에서_서버가_결정한다() {
        UserPlan current = withId(UserPlan.forUser(USER_ID, STANDARD_PLAN_ID), 500L);
        when(planTransitionService.resolveCurrentUserPlan(USER_ID, null)).thenReturn(current);

        PaymentOrderResponse response = writer.createOrder(USER_ID, PlanName.ENTERPRISE);

        // 응답·저장 금액 모두 plans.price_monthly 스냅샷이며, 클라이언트는 금액을 보낼 수단이 아예 없다.
        assertThat(response.amount()).isEqualTo(299000L);
        assertThat(response.planName()).isEqualTo("ENTERPRISE");
        assertThat(response.orderName()).isEqualTo("HajaCheck ENTERPRISE 플랜 구독");
        assertThat(response.orderId()).startsWith("haja-");
        // 토스 orderId 규격(6~64자)을 넘지 않는다.
        assertThat(response.orderId().length()).isBetween(6, 64);
    }

    @Test
    void FREE_대상_주문은_INVALID_INPUT으로_거절한다() {
        assertThatThrownBy(() -> writer.createOrder(USER_ID, PlanName.FREE))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT));
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void 회사구독_소유자가_아니면_주문을_만들지못한다() {
        UserPlan current = withId(UserPlan.forCompany(COMPANY_ID, STANDARD_PLAN_ID), 501L);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(companyUser));
        when(planTransitionService.resolveCurrentUserPlan(USER_ID, COMPANY_ID)).thenReturn(current);
        when(planTransitionService.requireSubscriptionOwner(USER_ID, COMPANY_ID, current))
                .thenThrow(new BusinessException(ErrorCode.PLAN_FORBIDDEN));

        assertThatThrownBy(() -> writer.createOrder(USER_ID, PlanName.ENTERPRISE))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLAN_FORBIDDEN));
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void 이미_사용중인_요금제는_주문을_만들지않고_409로_거절한다() {
        // 모의 결제 시절엔 "멱등 no-op(200)"이었지만, 실결제에서 no-op 주문을 내주면 아무것도 바뀌지
        // 않는 데 돈을 낸다 — 청구 전에 거절한다.
        UserPlan current = withId(UserPlan.forUser(USER_ID, STANDARD_PLAN_ID), 500L);
        when(planTransitionService.resolveCurrentUserPlan(USER_ID, null)).thenReturn(current);

        assertThatThrownBy(() -> writer.createOrder(USER_ID, PlanName.STANDARD))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLAN_ACTIVE_SUBSCRIPTION_CONFLICT));
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void 하향으로_한도초과가_생기면_주문단계에서_확인을_요구한다() {
        // #890 이관 — 확인 UX 없는 셀프 결제에서 조용히 동료를 정지시키지 않는다. 돈이 움직이기 전에 막는다.
        UserPlan current = withId(UserPlan.forCompany(COMPANY_ID, ENTERPRISE_PLAN_ID), 501L);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(companyUser));
        when(planTransitionService.resolveCurrentUserPlan(USER_ID, COMPANY_ID)).thenReturn(current);
        when(planDowngradeService.preview(anyLong(), any(Plan.class), any(Plan.class)))
                .thenReturn(new DowngradeOverflow(List.of(7L), 3));

        assertThatThrownBy(() -> writer.createOrder(USER_ID, PlanName.STANDARD))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLAN_DOWNGRADE_CONFIRMATION_REQUIRED));
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void 시크릿_미설정이면_주문단계에서_명확한_도메인에러로_실패한다() {
        // 조용한 NPE·빈 문자열 인증 시도 대신 fail-close(앱 기동은 막지 않는다).
        when(tossPaymentsClient.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> writer.createOrder(USER_ID, PlanName.ENTERPRISE))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_GATEWAY_ERROR));
    }

    // ── 승인 전 검증 ──

    @Test
    void 승인요청_금액이_저장금액과_다르면_PG호출전에_거절한다() {
        Payment payment = readyPayment(null);
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> writer.prepareConfirm(USER_ID,
                new PaymentConfirmRequest("test_payment_key", ORDER_ID, 1000L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_AMOUNT_MISMATCH));
    }

    @Test
    void 주문_소유자가_아니면_미존재와_같은_404로_응답해_실재여부를_흘리지않는다() {
        // 리뷰 P3 — 타인 소유를 403 으로 구분하면 남의 orderId 를 넣어보며 실재하는 주문을 열거할 수 있다.
        Payment payment = readyPayment(null);
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> writer.prepareConfirm(999L,
                new PaymentConfirmRequest("test_payment_key", ORDER_ID, 299000L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_ORDER_NOT_FOUND));
    }

    @Test
    void 존재하지않는_주문은_404다() {
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> writer.prepareConfirm(USER_ID,
                new PaymentConfirmRequest("test_payment_key", ORDER_ID, 299000L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_ORDER_NOT_FOUND));
    }

    @Test
    void 이미_실패한_주문은_재확정할수없고_상태를_흘리지않는다() {
        Payment payment = readyPayment(null);
        payment.markFailed("REJECT_CARD_COMPANY", "카드사 승인 거절");
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> writer.prepareConfirm(USER_ID,
                new PaymentConfirmRequest("test_payment_key", ORDER_ID, 299000L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_ORDER_NOT_FOUND));
    }

    @Test
    void 이미_승인된_주문은_멱등분기로_반환된다() {
        Payment payment = readyPayment(null);
        payment.markPaid("test_payment_key", PaymentMethod.CARD, "https://receipt", Instant.now());
        payment.linkUserPlan(600L);
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(payment));

        PaymentConfirmPreparation preparation = writer.prepareConfirm(USER_ID,
                new PaymentConfirmRequest("test_payment_key", ORDER_ID, 299000L));

        assertThat(preparation.alreadyPaid()).isTrue();
        assertThat(preparation.planApplicationPending()).isFalse();
    }

    @Test
    void 승인됐지만_플랜미반영이면_전이재시도_대상으로_표시한다() {
        Payment payment = readyPayment(null);
        payment.markPaid("test_payment_key", PaymentMethod.CARD, "https://receipt", Instant.now());
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(payment));

        PaymentConfirmPreparation preparation = writer.prepareConfirm(USER_ID,
                new PaymentConfirmRequest("test_payment_key", ORDER_ID, 299000L));

        assertThat(preparation.alreadyPaid()).isTrue();
        assertThat(preparation.planApplicationPending()).isTrue();
    }

    @Test
    void 주문생성후_소속이_바뀐_주문은_승인전에_차단한다() {
        // 다른 구독에 결제를 붙이면 엉뚱한 회사가 요금제를 받는다.
        Payment payment = readyPayment(COMPANY_ID);
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(payment));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser)); // 회사 이탈

        assertThatThrownBy(() -> writer.prepareConfirm(USER_ID,
                new PaymentConfirmRequest("test_payment_key", ORDER_ID, 299000L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_FORBIDDEN));
    }

    @Test
    void 이미_사용중인_요금제의_승인은_PG호출전에_차단한다() {
        // ⚠️ 리뷰 P1-B(중복 청구) — createOrder 만 막으면 READY 주문 2건을 미리 만든 뒤 순차 결제해
        // "2회 청구 + 구독 변화 0"을 만들 수 있고, 환불이 범위 밖이라 회수 수단이 없다.
        Payment payment = readyPayment(null);
        UserPlan current = withId(UserPlan.forUser(USER_ID, ENTERPRISE_PLAN_ID), 505L); // 이미 목표 플랜
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(payment));
        when(planTransitionService.resolveCurrentUserPlan(USER_ID, null)).thenReturn(current);

        assertThatThrownBy(() -> writer.prepareConfirm(USER_ID,
                new PaymentConfirmRequest("test_payment_key", ORDER_ID, 299000L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLAN_ACTIVE_SUBSCRIPTION_CONFLICT));

        // 주문은 READY 로 남는다 — 사용자가 나중에 다른 플랜을 결제할 여지를 태우지 않는다.
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
    }

    @Test
    void 유효시간이_지난_주문은_자동취소하고_거절한다() {
        // 리뷰 P2 — 금액이 스냅샷이라 시한이 없으면 요금 인상 직전 주문을 쟁여뒀다가 구가격으로 결제할 수 있다.
        Payment payment = readyPayment(null);
        setRequestedAt(payment, Instant.now().minus(Duration.ofHours(2)));
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> writer.prepareConfirm(USER_ID,
                new PaymentConfirmRequest("test_payment_key", ORDER_ID, 299000L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_ORDER_NOT_FOUND));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(payment.getFailureCode()).isEqualTo(Payment.FAILURE_CODE_EXPIRED);
    }

    @Test
    void 유효한_기존_READY주문이_있으면_새로_만들지않고_재사용한다() {
        // 리뷰 P1-B 근본 원인 차단 — 결제창을 여러 번 열어도 주문이 쌓이지 않는다.
        UserPlan current = withId(UserPlan.forUser(USER_ID, STANDARD_PLAN_ID), 500L);
        Payment existing = readyPayment(null);
        when(planTransitionService.resolveCurrentUserPlan(USER_ID, null)).thenReturn(current);
        when(paymentRepository.findFirstByUserIdAndPlanIdAndStatusAndRequestedAtAfterOrderByRequestedAtDesc(
                eq(USER_ID), eq(ENTERPRISE_PLAN_ID), eq(PaymentStatus.READY), any(Instant.class)))
                .thenReturn(Optional.of(existing));

        PaymentOrderResponse response = writer.createOrder(USER_ID, PlanName.ENTERPRISE);

        assertThat(response.orderId()).isEqualTo(existing.getOrderId());
        assertThat(response.amount()).isEqualTo(299000L);
        verify(paymentRepository, never()).save(any());
    }

    // ── 승인 후 반영 ──

    @Test
    void 승인되지않은_결제로는_플랜을_전이하지않는다() {
        Payment payment = readyPayment(null);
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> writer.applyPlanTransition(PAYMENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_GATEWAY_ERROR));
        verify(planTransitionService, never()).transitionTo(any(), any(), any(), any());
    }

    @Test
    void 승인성공시_구독을_전이하고_결제에_연결한다() {
        Payment payment = readyPayment(null);
        payment.markPaid("test_payment_key", PaymentMethod.CARD, "https://receipt", Instant.now());
        UserPlan current = withId(UserPlan.forUser(USER_ID, STANDARD_PLAN_ID), 500L);
        UserPlan renewed = withId(UserPlan.forUser(USER_ID, ENTERPRISE_PLAN_ID), 501L);
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        when(planTransitionService.resolveCurrentUserPlan(USER_ID, null)).thenReturn(current);
        when(planTransitionService.transitionTo(USER_ID, null, current, enterprisePlan)).thenReturn(renewed);

        writer.applyPlanTransition(PAYMENT_ID);

        assertThat(payment.getUserPlanId()).isEqualTo(501L);
    }

    @Test
    void 이미_반영된_결제는_다시_전이하지않는다() {
        Payment payment = readyPayment(null);
        payment.markPaid("test_payment_key", PaymentMethod.CARD, "https://receipt", Instant.now());
        payment.linkUserPlan(501L);
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));

        writer.applyPlanTransition(PAYMENT_ID);

        verify(planTransitionService, never()).transitionTo(any(), any(), any(), any());
    }

    @Test
    void 승인과_전이사이에_이미_목표플랜이_되었으면_연결만_한다() {
        // 불필요한 이력 행·사용량 이월 반복을 막는다.
        Payment payment = readyPayment(null);
        payment.markPaid("test_payment_key", PaymentMethod.CARD, "https://receipt", Instant.now());
        UserPlan current = withId(UserPlan.forUser(USER_ID, ENTERPRISE_PLAN_ID), 505L);
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        when(planTransitionService.resolveCurrentUserPlan(USER_ID, null)).thenReturn(current);

        writer.applyPlanTransition(PAYMENT_ID);

        assertThat(payment.getUserPlanId()).isEqualTo(505L);
        verify(planTransitionService, never()).transitionTo(any(), any(), any(), any());
    }

    @Test
    void 승인실패기록은_이미_승인된_결제를_덮지않는다() {
        Payment payment = readyPayment(null);
        payment.markPaid("test_payment_key", PaymentMethod.CARD, "https://receipt", Instant.now());
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));

        writer.markFailed(PAYMENT_ID, "ALREADY_PROCESSED_PAYMENT", "이미 처리된 결제");

        // 같은 orderId 동시 승인 경합에서 뒤늦게 도착한 실패가 결제 원장을 거짓으로 만들면 안 된다.
        assertThat(payment.isPaid()).isTrue();
        assertThat(payment.getFailureCode()).isNull();
    }

    // ── fixtures ──

    private Payment readyPayment(Long companyId) {
        Payment payment = Payment.createOrder(ORDER_ID, USER_ID, companyId, ENTERPRISE_PLAN_ID,
                PlanName.ENTERPRISE, new BigDecimal("299000.00"));
        setId(payment, PAYMENT_ID);
        return payment;
    }

    private static User user(Long id, Long companyId) {
        User u = User.builder()
                .email("user" + id + "@haja.com")
                .name("사용자" + id)
                .passwordHash("$2a$hashed")
                .companyId(companyId)
                .build();
        setId(u, id);
        return u;
    }

    private static Plan plan(PlanName name, Long id, BigDecimal price, Integer maxSeats) {
        Plan p = Plan.create(name, 10, 1000, maxSeats, false, true, true, price);
        setId(p, id);
        return p;
    }

    private static UserPlan withId(UserPlan userPlan, Long id) {
        setId(userPlan, id);
        return userPlan;
    }

    /** 테스트 전용 — 주문 생성 시각을 과거로 밀어 유효시간 초과 상황을 만든다. */
    private static void setRequestedAt(Payment payment, Instant requestedAt) {
        try {
            Field field = Payment.class.getDeclaredField("requestedAt");
            field.setAccessible(true);
            field.set(payment, requestedAt);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 테스트 전용 — IDENTITY 생성 id 를 리플렉션으로 세팅(엔티티에 setter 를 두지 않기 위함). */
    private static void setId(Object entity, Long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
