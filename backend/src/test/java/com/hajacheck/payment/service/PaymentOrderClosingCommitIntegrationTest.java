package com.hajacheck.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import com.hajacheck.payment.config.TossPaymentsProperties;
import com.hajacheck.payment.dto.PaymentConfirmRequest;
import com.hajacheck.payment.dto.PaymentOrderRequest;
import com.hajacheck.payment.dto.PaymentOrderResponse;
import com.hajacheck.payment.entity.Payment;
import com.hajacheck.payment.entity.PaymentStatus;
import com.hajacheck.payment.repository.PaymentRepository;
import com.hajacheck.support.PostgresTestSupport;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 결제 주문 자동 취소가 <b>실제로 커밋되는지</b>를 증명하는 통합 테스트(#988 리뷰 P2).
 *
 * <p><b>왜 별도 클래스인가 — 기존 테스트가 이 결함을 못 잡은 이유</b>
 * <ul>
 *   <li>단위 테스트({@code PaymentWriterTest})는 목 리포지토리를 쓰므로 <b>메모리 엔티티 상태</b>만
 *       단언한다. 트랜잭션이 롤백되는지 여부는 애초에 관찰할 수 없다.</li>
 *   <li>{@code PaymentControllerTest}는 클래스 레벨 {@code @Transactional} 이라 테스트가 하나의
 *       트랜잭션으로 묶이고 끝나면 롤백된다 — <b>커밋/롤백 경계 자체를 검증할 수 없다</b>.</li>
 * </ul>
 * 그래서 이 클래스는 <b>{@code @Transactional} 을 달지 않는다</b>. 각 리포지토리 호출이 자기 트랜잭션에서
 * 실행되므로, 서비스 호출이 끝난 뒤의 조회는 <b>커밋된 상태</b>만 본다. 대신 롤백으로 정리되지 않으므로
 * {@link #cleanUp()} 이 직접 지운다.
 *
 * <p>고정하는 계약: 만료·시도상한 초과로 거절할 때 {@code status=CANCELED} 가 <b>커밋</b>되어야 한다.
 * 예전 구현은 검증 트랜잭션 안에서 상태를 바꾼 직후 {@code BusinessException}(RuntimeException)을 던져
 * 그 UPDATE 가 롤백됐고, 응답(404)과 재사용 제외(시간 조건)는 정상이라 겉으로 드러나지 않았다.
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentOrderClosingCommitIntegrationTest extends PostgresTestSupport {

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserPlanRepository userPlanRepository;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private TossPaymentsProperties properties;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private TossPaymentsClient tossPaymentsClient;

    private User user;
    private UserPlan userPlan;

    @BeforeEach
    void setUp() {
        when(tossPaymentsClient.isConfigured()).thenReturn(true);
        // 시드 요금제(FREE/STANDARD/ENTERPRISE)를 그대로 쓴다 — 이 테스트는 롤백되지 않으므로 공용
        // 시드 행을 지우거나 바꾸면 같은 컨테이너를 쓰는 다른 테스트를 오염시킨다.
        Plan standard = planRepository.findByName(PlanName.STANDARD).orElseThrow();
        user = userRepository.saveAndFlush(User.builder()
                .email("payment-closing-" + System.nanoTime() + "@haja.test")
                .name("만료테스트")
                .role(Role.USER)
                .passwordHash("$2a$10$hashed")
                .status(UserStatus.ACTIVE)
                .build());
        userPlan = userPlanRepository.saveAndFlush(UserPlan.forUser(user.getId(), standard.getId()));
    }

    @AfterEach
    void cleanUp() {
        // @Transactional 이 없어 롤백 정리가 되지 않는다 — 만든 행을 직접 지운다(FK 역순).
        jdbcTemplate.update("delete from payments where user_id = ?", user.getId());
        jdbcTemplate.update("delete from usage_counters where user_plan_id in "
                + "(select id from user_plans where user_id = ?)", user.getId());
        jdbcTemplate.update("delete from user_plans where user_id = ?", user.getId());
        jdbcTemplate.update("delete from users where id = ?", user.getId());
    }

    @Test
    void 유효시간이_지난_주문의_자동취소가_실제로_커밋된다() {
        PaymentOrderResponse order = paymentService.createOrder(
                user.getId(), new PaymentOrderRequest(PlanName.ENTERPRISE));
        expireOrder(order.orderId());

        assertThatThrownBy(() -> paymentService.confirm(user.getId(),
                new PaymentConfirmRequest("test_payment_key", order.orderId(), order.amount())))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_ORDER_NOT_FOUND));

        // ⚠️ 이 단언이 핵심이다 — 테스트에 트랜잭션이 없으므로 이 조회는 커밋된 값만 볼 수 있다.
        //    예전 구현에서는 여기가 READY 로 남아 실패한다(취소 UPDATE 가 롤백됐으므로).
        Payment closed = paymentRepository.findByOrderId(order.orderId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(closed.getFailureCode()).isEqualTo(Payment.FAILURE_CODE_EXPIRED);

        // DB 를 직접 조회해서도 확인한다(영속성 컨텍스트 캐시가 아니라 실제 행이 바뀌었는지).
        String persistedStatus = jdbcTemplate.queryForObject(
                "select status::text from payments where order_id = ?", String.class, order.orderId());
        assertThat(persistedStatus).isEqualTo("CANCELED");

        verify(tossPaymentsClient, never()).confirm(anyString(), anyString(), anyLong());
    }

    @Test
    void 만료된_주문은_취소된_뒤_새_주문발급을_막지_않는다() {
        // 만료 취소가 커밋되지 않으면 그 행이 READY 로 남아 재사용 후보로 계속 잡힐 수 있다.
        PaymentOrderResponse first = paymentService.createOrder(
                user.getId(), new PaymentOrderRequest(PlanName.ENTERPRISE));
        expireOrder(first.orderId());

        assertThatThrownBy(() -> paymentService.confirm(user.getId(),
                new PaymentConfirmRequest("test_payment_key", first.orderId(), first.amount())))
                .isInstanceOf(BusinessException.class);

        PaymentOrderResponse second = paymentService.createOrder(
                user.getId(), new PaymentOrderRequest(PlanName.ENTERPRISE));
        assertThat(second.orderId()).isNotEqualTo(first.orderId());
        assertThat(paymentRepository.findByOrderId(first.orderId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.CANCELED);
    }

    @Test
    void 승인시도_상한을_넘기면_주문이_취소로_커밋되고_PG를_더이상_부르지않는다() {
        // 보안 리뷰 P2 — "결과 불명이면 READY 유지"가 열어버린 무한 confirm 반복의 상한.
        PaymentOrderResponse order = paymentService.createOrder(
                user.getId(), new PaymentOrderRequest(PlanName.ENTERPRISE));
        PaymentConfirmRequest request =
                new PaymentConfirmRequest("test_payment_key", order.orderId(), order.amount());
        when(tossPaymentsClient.confirm(anyString(), anyString(), anyLong()))
                .thenThrow(TossPaymentApprovalException.outcomeUnknown(
                        TossPaymentApprovalException.CODE_UNREACHABLE, "결제 서버에 연결하지 못했습니다."));

        // 상한만큼은 결과 불명(502)으로 실패하되 주문이 살아 있어 재확정이 가능하다.
        for (int attempt = 0; attempt < properties.getMaxConfirmAttempts(); attempt++) {
            assertThatThrownBy(() -> paymentService.confirm(user.getId(), request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.PAYMENT_GATEWAY_ERROR));
        }
        // 시도 횟수 증가도 커밋돼야 한다 — 롤백되면 상한이 영영 차지 않는다.
        assertThat(paymentRepository.findByOrderId(order.orderId()).orElseThrow()
                .getConfirmAttemptCount()).isEqualTo(properties.getMaxConfirmAttempts());

        // 상한을 넘긴 다음 요청은 PG 를 부르지 않고 404 + 주문 취소.
        assertThatThrownBy(() -> paymentService.confirm(user.getId(), request))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_ORDER_NOT_FOUND));

        Payment closed = paymentRepository.findByOrderId(order.orderId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(closed.getFailureCode()).isEqualTo(Payment.FAILURE_CODE_ATTEMPT_LIMIT);
        // 상한만큼만 호출됐고 그 뒤로는 늘지 않는다.
        verify(tossPaymentsClient, org.mockito.Mockito.times(properties.getMaxConfirmAttempts()))
                .confirm(anyString(), anyString(), anyLong());
    }

    /** 주문 생성 시각을 TTL 밖으로 밀어 만료 상황을 만든다(엔티티에 setter 를 두지 않으므로 DB 직접 갱신). */
    private void expireOrder(String orderId) {
        Instant expiredAt = Instant.now().minus(properties.getOrderTtl()).minus(Duration.ofMinutes(1));
        jdbcTemplate.update("update payments set requested_at = ? where order_id = ?",
                java.sql.Timestamp.from(expiredAt), orderId);
    }
}
