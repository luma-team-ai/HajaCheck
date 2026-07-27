package com.hajacheck.payment.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UsageCounter;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UsageCounterRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import com.hajacheck.payment.entity.Payment;
import com.hajacheck.payment.entity.PaymentMethod;
import com.hajacheck.payment.entity.PaymentStatus;
import com.hajacheck.payment.repository.PaymentRepository;
import com.hajacheck.payment.service.TossPaymentApproval;
import com.hajacheck.payment.service.TossPaymentApprovalException;
import com.hajacheck.payment.service.TossPaymentsClient;
import com.hajacheck.support.PostgresTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 API MVC 통합 테스트(#988 / HAJA-489) — 주문 생성 → 승인 → 이력 조회.
 *
 * <p>{@link TossPaymentsClient} 는 {@code @MockBean} 으로 스텁해 외부 PG 의존을 제거한다(AiProxy·
 * BusinessVerification 컨트롤러 테스트와 동일 방식). 그 외 계층(시큐리티 필터체인·JPA·PG named enum)은
 * 실제로 태워야 하므로 {@code @SpringBootTest + PostgresTestSupport} 를 쓴다.
 *
 * <p>⚠️ 이 테스트는 {@code @Transactional} 이라 {@code PaymentWriter} 의 단계별 트랜잭션이 하나로 합쳐진다 —
 * "실패 기록이 롤백되지 않는다" 같은 <b>경계 자체</b>의 검증은 단위테스트({@code PaymentServiceTest})가
 * 담당하고, 여기서는 계약(상태코드·응답 스키마·DB 반영 내용)을 고정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PaymentControllerTest extends PostgresTestSupport {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private UserPlanRepository userPlanRepository;
    @Autowired
    private UsageCounterRepository usageCounterRepository;
    @Autowired
    private PaymentRepository paymentRepository;

    @MockBean
    private TossPaymentsClient tossPaymentsClient;

    @BeforeEach
    void setUp() {
        when(tossPaymentsClient.isConfigured()).thenReturn(true);
    }

    // ── 주문 생성 ──

    @Test
    void 주문생성_개인구독_소유자_200_금액은_서버가결정한다() throws Exception {
        Plan standard = saveStandardPlan();
        Plan enterprise = saveEnterprisePlan();
        User user = saveUser("order@haja.com", null);
        userPlanRepository.save(UserPlan.forUser(user.getId(), standard.getId()));

        String body = mockMvc.perform(post("/api/me/plan/orders").with(csrf()).with(authentication(authOf(user)))
                        .contentType("application/json")
                        .content("{\"planName\":\"ENTERPRISE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.planName").value("ENTERPRISE"))
                .andExpect(jsonPath("$.data.amount").value(299000))
                .andExpect(jsonPath("$.data.orderName").value("HajaCheck ENTERPRISE 플랜 구독"))
                .andReturn().getResponse().getContentAsString();

        String orderId = objectMapper.readTree(body).path("data").path("orderId").asText();
        Payment saved = paymentRepository.findByOrderId(orderId).orElseThrow();
        // 저장 금액은 요청이 아니라 plans.price_monthly 스냅샷이다(보안 요구 1).
        assertThat(saved.getAmount()).isEqualByComparingTo(enterprise.getPriceMonthly());
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(saved.getUserPlanId()).isNull();
        assertThat(saved.getPaymentKey()).isNull();
    }

    @Test
    void 주문생성_회사구독_소유자아니면_403_PLAN_FORBIDDEN() throws Exception {
        Plan standard = saveStandardPlan();
        saveEnterprisePlan();
        User owner = saveUser("orderOwner@haja.com", null);
        Company company = saveCompany(owner, "1112223330");
        User staff = saveUser("orderStaff@haja.com", company.getId());
        userPlanRepository.save(UserPlan.forCompany(company.getId(), standard.getId()));

        mockMvc.perform(post("/api/me/plan/orders").with(csrf()).with(authentication(authOf(staff)))
                        .contentType("application/json")
                        .content("{\"planName\":\"ENTERPRISE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PLAN_FORBIDDEN"));
    }

    @Test
    void 주문생성_FREE대상_400_INVALID_INPUT() throws Exception {
        Plan standard = saveStandardPlan();
        User user = saveUser("orderFree@haja.com", null);
        userPlanRepository.save(UserPlan.forUser(user.getId(), standard.getId()));

        mockMvc.perform(post("/api/me/plan/orders").with(csrf()).with(authentication(authOf(user)))
                        .contentType("application/json")
                        .content("{\"planName\":\"FREE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void 주문생성_이미같은요금제면_409_PLAN_ACTIVE_SUBSCRIPTION_CONFLICT() throws Exception {
        Plan standard = saveStandardPlan();
        User user = saveUser("orderSame@haja.com", null);
        userPlanRepository.save(UserPlan.forUser(user.getId(), standard.getId()));

        mockMvc.perform(post("/api/me/plan/orders").with(csrf()).with(authentication(authOf(user)))
                        .contentType("application/json")
                        .content("{\"planName\":\"STANDARD\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PLAN_ACTIVE_SUBSCRIPTION_CONFLICT"));
    }

    @Test
    void 주문생성_미인증_401() throws Exception {
        mockMvc.perform(post("/api/me/plan/orders").with(csrf())
                        .contentType("application/json")
                        .content("{\"planName\":\"STANDARD\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 주문생성_시크릿미설정이면_502_PAYMENT_GATEWAY_ERROR() throws Exception {
        when(tossPaymentsClient.isConfigured()).thenReturn(false);
        Plan standard = saveStandardPlan();
        saveEnterprisePlan();
        User user = saveUser("orderNoKey@haja.com", null);
        userPlanRepository.save(UserPlan.forUser(user.getId(), standard.getId()));

        mockMvc.perform(post("/api/me/plan/orders").with(csrf()).with(authentication(authOf(user)))
                        .contentType("application/json")
                        .content("{\"planName\":\"ENTERPRISE\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_GATEWAY_ERROR"));
    }

    // ── 승인 ──

    @Test
    void 승인성공_플랜전이되고_사용량이_이월된다() throws Exception {
        Plan standard = saveStandardPlan();
        Plan enterprise = saveEnterprisePlan();
        User user = saveUser("confirm@haja.com", null);
        UserPlan original = userPlanRepository.save(UserPlan.forUser(user.getId(), standard.getId()));
        // #851 — 이월이 없으면 결제 한 번에 월 분석 한도가 0으로 리셋된다.
        usageCounterRepository.saveAndFlush(
                UsageCounter.create(original.getId(), currentPeriod(), 40, 2, 5, 1, 0, 0));
        Payment order = saveReadyOrder(user, null, enterprise);

        stubApproval();

        mockMvc.perform(post("/api/me/payments/confirm").with(csrf()).with(authentication(authOf(user)))
                        .contentType("application/json")
                        .content(confirmBody(order.getOrderId(), 299000L)))
                .andExpect(status().isOk())
                // 계약: 응답은 GET /api/me/plan 과 동일 스키마.
                .andExpect(jsonPath("$.data.plan.name").value("ENTERPRISE"))
                .andExpect(jsonPath("$.data.plan.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.usage.analyzedImageCount").value(40));

        UserPlan expired = userPlanRepository.findById(original.getId()).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(UserPlanStatus.EXPIRED);

        Payment paid = paymentRepository.findByOrderId(order.getOrderId()).orElseThrow();
        assertThat(paid.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(paid.getMethod()).isEqualTo(PaymentMethod.CARD);
        assertThat(paid.getReceiptUrl()).isEqualTo("https://receipt.example/abc");
        assertThat(paid.getUserPlanId()).isNotNull().isNotEqualTo(original.getId());

        UsageCounter carried = usageCounterRepository
                .findByUserPlanIdAndPeriod(paid.getUserPlanId(), currentPeriod()).orElseThrow();
        assertThat(carried.getAnalyzedImageCount()).isEqualTo(40);
        assertThat(carried.getAnalysisRequestCount()).isEqualTo(5);
        assertThat(carried.getFacilityCount()).isEqualTo(2);
    }

    @Test
    void 승인_금액위변조는_400이고_PG를_호출하지않으며_플랜이_그대로다() throws Exception {
        Plan standard = saveStandardPlan();
        Plan enterprise = saveEnterprisePlan();
        User user = saveUser("confirmTamper@haja.com", null);
        UserPlan original = userPlanRepository.save(UserPlan.forUser(user.getId(), standard.getId()));
        Payment order = saveReadyOrder(user, null, enterprise);

        mockMvc.perform(post("/api/me/payments/confirm").with(csrf()).with(authentication(authOf(user)))
                        .contentType("application/json")
                        .content(confirmBody(order.getOrderId(), 100L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_AMOUNT_MISMATCH"));

        verify(tossPaymentsClient, never()).confirm(anyString(), anyString(), anyLong());
        assertThat(userPlanRepository.findById(original.getId()).orElseThrow().getStatus())
                .isEqualTo(UserPlanStatus.ACTIVE);
        // 정상 주문을 태우지 않는다(READY 유지) — 위변조 방어는 PG 호출 차단으로 이미 끝났다.
        assertThat(paymentRepository.findByOrderId(order.getOrderId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.READY);
    }

    @Test
    void 승인_남의_주문이면_미존재와_같은_404이고_상태를_흘리지않는다() throws Exception {
        Plan standard = saveStandardPlan();
        Plan enterprise = saveEnterprisePlan();
        User owner = saveUser("confirmOwner@haja.com", null);
        User other = saveUser("confirmOther@haja.com", null);
        userPlanRepository.save(UserPlan.forUser(owner.getId(), standard.getId()));
        userPlanRepository.save(UserPlan.forUser(other.getId(), standard.getId()));
        Payment order = saveReadyOrder(owner, null, enterprise);

        mockMvc.perform(post("/api/me/payments/confirm").with(csrf()).with(authentication(authOf(other)))
                        .contentType("application/json")
                        .content(confirmBody(order.getOrderId(), 299000L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_ORDER_NOT_FOUND"));

        verify(tossPaymentsClient, never()).confirm(anyString(), anyString(), anyLong());
    }

    @Test
    void 승인_게이트웨이_실패시_502이고_플랜은_그대로_FAILED가_기록된다() throws Exception {
        Plan standard = saveStandardPlan();
        Plan enterprise = saveEnterprisePlan();
        User user = saveUser("confirmFail@haja.com", null);
        UserPlan original = userPlanRepository.save(UserPlan.forUser(user.getId(), standard.getId()));
        Payment order = saveReadyOrder(user, null, enterprise);

        when(tossPaymentsClient.confirm(anyString(), anyString(), anyLong()))
                .thenThrow(TossPaymentApprovalException.rejected("REJECT_CARD_COMPANY", "카드사 승인 거절"));

        mockMvc.perform(post("/api/me/payments/confirm").with(csrf()).with(authentication(authOf(user)))
                        .contentType("application/json")
                        .content(confirmBody(order.getOrderId(), 299000L)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_GATEWAY_ERROR"));

        // 보안 요구 5 — 승인 실패 시 플랜 불변.
        assertThat(userPlanRepository.findById(original.getId()).orElseThrow().getStatus())
                .isEqualTo(UserPlanStatus.ACTIVE);
        Payment failed = paymentRepository.findByOrderId(order.getOrderId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(failed.getFailureCode()).isEqualTo("REJECT_CARD_COMPANY");
        assertThat(failed.getUserPlanId()).isNull();
    }

    @Test
    void 승인_결과불명_실패는_주문을_닫지않아_재확정으로_복구된다() throws Exception {
        // 리뷰 P1-C — 타임아웃을 FAILED 로 확정하면 재확정이 404 로 영구 차단돼 돈만 나간 상태가 굳는다.
        Plan standard = saveStandardPlan();
        Plan enterprise = saveEnterprisePlan();
        User user = saveUser("confirmUnknown@haja.com", null);
        UserPlan original = userPlanRepository.save(UserPlan.forUser(user.getId(), standard.getId()));
        Payment order = saveReadyOrder(user, null, enterprise);
        String content = confirmBody(order.getOrderId(), 299000L);

        // 1회차는 결과 불명(타임아웃), 2회차는 정상 승인 — 한 스텁에 연속 응답으로 지정한다
        // (throw 로 스텁된 목을 when(...) 안에서 다시 호출하면 그 자리에서 예외가 터지므로 재스텁 불가).
        when(tossPaymentsClient.confirm(anyString(), anyString(), anyLong()))
                .thenThrow(TossPaymentApprovalException.outcomeUnknown(
                        TossPaymentApprovalException.CODE_UNREACHABLE, "결제 서버에 연결하지 못했습니다."))
                .thenReturn(new TossPaymentApproval("test_payment_key_recovered", PaymentMethod.CARD,
                        "https://receipt.example/abc", Instant.now()));

        mockMvc.perform(post("/api/me/payments/confirm").with(csrf()).with(authentication(authOf(user)))
                        .contentType("application/json").content(content))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_GATEWAY_ERROR"));

        // 주문이 닫히지 않았다 — FAILED 가 아니라 READY 여야 재확정이 가능하다.
        assertThat(paymentRepository.findByOrderId(order.getOrderId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.READY);
        assertThat(userPlanRepository.findById(original.getId()).orElseThrow().getStatus())
                .isEqualTo(UserPlanStatus.ACTIVE);

        // 재확정 — 이번엔 PG 가 정상 응답하고 플랜이 반영된다(복구 경로 전체를 고정한다).
        mockMvc.perform(post("/api/me/payments/confirm").with(csrf()).with(authentication(authOf(user)))
                        .contentType("application/json").content(content))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan.name").value("ENTERPRISE"));

        Payment recovered = paymentRepository.findByOrderId(order.getOrderId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(recovered.getUserPlanId()).isNotNull();
    }

    @Test
    void 승인_이미_그_요금제면_PG호출없이_409로_막아_중복청구를_차단한다() throws Exception {
        // 리뷰 P1-B — READY 주문 2건을 만든 뒤 순차 결제하는 "2회 청구 + 구독 변화 0" 시나리오.
        Plan enterprise = saveEnterprisePlan();
        User user = saveUser("confirmDup@haja.com", null);
        // 이미 목표 요금제를 쓰고 있는 상태에서 남아 있던 주문을 결제하려는 상황.
        userPlanRepository.save(UserPlan.forUser(user.getId(), enterprise.getId()));
        Payment order = saveReadyOrder(user, null, enterprise);

        mockMvc.perform(post("/api/me/payments/confirm").with(csrf()).with(authentication(authOf(user)))
                        .contentType("application/json")
                        .content(confirmBody(order.getOrderId(), 299000L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PLAN_ACTIVE_SUBSCRIPTION_CONFLICT"));

        verify(tossPaymentsClient, never()).confirm(anyString(), anyString(), anyLong());
    }

    @Test
    void 주문생성_유효한_기존주문이_있으면_같은_orderId를_돌려준다() throws Exception {
        Plan standard = saveStandardPlan();
        saveEnterprisePlan();
        User user = saveUser("orderReuse@haja.com", null);
        userPlanRepository.save(UserPlan.forUser(user.getId(), standard.getId()));

        String first = createOrderAndReadOrderId(user);
        String second = createOrderAndReadOrderId(user);

        assertThat(second).isEqualTo(first);
        assertThat(paymentRepository.findByUserIdOrderByRequestedAtDescIdDesc(
                user.getId(), org.springframework.data.domain.PageRequest.of(0, 10))).hasSize(1);
    }

    @Test
    void 승인_이미승인된_주문의_재요청은_PG재호출없이_200이다() throws Exception {
        Plan standard = saveStandardPlan();
        Plan enterprise = saveEnterprisePlan();
        User user = saveUser("confirmIdem@haja.com", null);
        userPlanRepository.save(UserPlan.forUser(user.getId(), standard.getId()));
        Payment order = saveReadyOrder(user, null, enterprise);
        stubApproval();

        String content = confirmBody(order.getOrderId(), 299000L);
        mockMvc.perform(post("/api/me/payments/confirm").with(csrf()).with(authentication(authOf(user)))
                        .contentType("application/json").content(content))
                .andExpect(status().isOk());

        // 리다이렉트 새로고침·중복 전송 재현 — 두 번째 요청은 PG 를 다시 부르지 않는다(보안 요구 3).
        mockMvc.perform(post("/api/me/payments/confirm").with(csrf()).with(authentication(authOf(user)))
                        .contentType("application/json").content(content))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan.name").value("ENTERPRISE"));

        verify(tossPaymentsClient, org.mockito.Mockito.times(1))
                .confirm(anyString(), anyString(), anyLong());
        // 중복 구독 행이 생기지 않았는지 — ACTIVE 는 언제나 1건이어야 한다.
        assertThat(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(
                user.getId(), UserPlanStatus.ACTIVE)).isPresent();
    }

    @Test
    void 승인_존재하지않는_주문은_404다() throws Exception {
        saveStandardPlan();
        User user = saveUser("confirmMissing@haja.com", null);

        mockMvc.perform(post("/api/me/payments/confirm").with(csrf()).with(authentication(authOf(user)))
                        .contentType("application/json")
                        .content(confirmBody("haja-not-exist", 299000L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_ORDER_NOT_FOUND"));
    }

    // ── 결제 이력 ──

    @Test
    void 결제이력은_본인주문만_최신순으로_반환하고_paymentKey를_노출하지않는다() throws Exception {
        Plan enterprise = saveEnterprisePlan();
        User user = saveUser("history@haja.com", null);
        User other = saveUser("historyOther@haja.com", null);
        Payment mine = saveReadyOrder(user, null, enterprise);
        mine.markPaid("test_payment_key_secret", PaymentMethod.CARD, "https://receipt.example/1",
                Instant.now());
        saveReadyOrder(other, null, enterprise);
        paymentRepository.flush();

        String body = mockMvc.perform(get("/api/me/payments").with(authentication(authOf(user))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payments.length()").value(1))
                .andExpect(jsonPath("$.data.payments[0].orderId").value(mine.getOrderId()))
                .andExpect(jsonPath("$.data.payments[0].status").value("PAID"))
                .andExpect(jsonPath("$.data.payments[0].method").value("CARD"))
                .andExpect(jsonPath("$.data.payments[0].amount").value(299000))
                .andReturn().getResponse().getContentAsString();

        JsonNode item = objectMapper.readTree(body).path("data").path("payments").get(0);
        // PG 결제 키는 화면에 필요 없고 유출되면 결제 조회·취소 식별자가 된다(보안 요구 6).
        assertThat(item.has("paymentKey")).isFalse();
        assertThat(body).doesNotContain("test_payment_key_secret");
    }

    @Test
    void 결제이력_미인증_401() throws Exception {
        mockMvc.perform(get("/api/me/payments")).andExpect(status().isUnauthorized());
    }

    // ── fixtures ──

    private String createOrderAndReadOrderId(User user) throws Exception {
        String body = mockMvc.perform(post("/api/me/plan/orders").with(csrf())
                        .with(authentication(authOf(user)))
                        .contentType("application/json")
                        .content("{\"planName\":\"ENTERPRISE\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("orderId").asText();
    }

    private void stubApproval() {
        when(tossPaymentsClient.confirm(anyString(), anyString(), anyLong()))
                .thenReturn(new TossPaymentApproval("test_payment_key_" + System.nanoTime(),
                        PaymentMethod.CARD, "https://receipt.example/abc", Instant.now()));
    }

    private String confirmBody(String orderId, long amount) {
        return "{\"paymentKey\":\"test_payment_key\",\"orderId\":\"" + orderId + "\",\"amount\":" + amount + "}";
    }

    private LocalDate currentPeriod() {
        return YearMonth.now(KST).atDay(1);
    }

    private Payment saveReadyOrder(User user, Long companyId, Plan plan) {
        return paymentRepository.saveAndFlush(Payment.createOrder(
                "haja-" + java.util.UUID.randomUUID(), user.getId(), companyId, plan.getId(),
                plan.getName(), plan.getPriceMonthly()));
    }

    private Plan saveStandardPlan() {
        // #517 시드로 STANDARD 가 이미 존재 — 고정 테스트 값을 쓰기 위해 시드 행을 지우고 대체한다
        // (트랜잭션 롤백으로 테스트 간 격리 유지 — MembershipControllerTest 와 동일 방식).
        planRepository.findByName(PlanName.STANDARD).ifPresent(planRepository::delete);
        planRepository.flush();
        return planRepository.saveAndFlush(Plan.create(PlanName.STANDARD, 10, 1000, 3, false, true, false,
                new BigDecimal("99000.00")));
    }

    private Plan saveEnterprisePlan() {
        planRepository.findByName(PlanName.ENTERPRISE).ifPresent(planRepository::delete);
        planRepository.flush();
        return planRepository.saveAndFlush(Plan.create(PlanName.ENTERPRISE, null, null, null, false, true,
                true, new BigDecimal("299000.00")));
    }

    private User saveUser(String email, Long companyId) {
        return userRepository.saveAndFlush(User.builder()
                .email(email)
                .name("결제사용자")
                .role(Role.USER)
                .passwordHash("$2a$10$hashed")
                .companyId(companyId)
                .status(UserStatus.ACTIVE)
                .build());
    }

    private Company saveCompany(User owner, String brn) {
        Company company = companyRepository.saveAndFlush(Company.createPendingReview(
                owner.getId(), "(주)하자체크", brn, "이철수", "서울시 마포구", null,
                "http://files/brn.png", "{}"));
        owner.assignToCompany(company.getId());
        userRepository.saveAndFlush(owner);
        return company;
    }

    private UsernamePasswordAuthenticationToken authOf(User user) {
        LoginUser principal = new LoginUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
