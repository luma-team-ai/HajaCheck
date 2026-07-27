package com.hajacheck.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.dto.MyPlanResponse;
import com.hajacheck.membership.service.MembershipService;
import com.hajacheck.payment.config.TossPaymentsProperties;
import com.hajacheck.payment.dto.PaymentConfirmRequest;
import com.hajacheck.payment.entity.PaymentMethod;
import com.hajacheck.payment.repository.PaymentRepository;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * PaymentService 오케스트레이션 단위테스트(#988 / HAJA-489) — <b>순서와 경계</b>를 고정한다.
 *
 * <p>이 클래스가 지키는 계약:
 * <ul>
 *   <li>검증에 실패하면 PG 를 <b>호출하지 않는다</b>(금액 위변조 시 승인 호출 자체가 없어야 한다).</li>
 *   <li>이미 승인된 orderId 재요청은 PG 재호출 없이 200(멱등).</li>
 *   <li>PG 실패 시 결제 원장에 FAILED 를 남기고, <b>플랜 전이는 시도조차 하지 않는다</b>.</li>
 *   <li>승인 성공 시 "승인 기록 → 플랜 전이" 순서로 반영한다.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long PAYMENT_ID = 900L;
    private static final String ORDER_ID = "haja-00000000-0000-0000-0000-000000000001";
    private static final String PAYMENT_KEY = "test_payment_key";
    private static final long AMOUNT = 299000L;

    @Mock
    private PaymentWriter paymentWriter;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private TossPaymentsClient tossPaymentsClient;
    @Mock
    private MembershipService membershipService;

    private PaymentService service;

    @BeforeEach
    void setUp() {
        service = new PaymentService(paymentWriter, paymentRepository, tossPaymentsClient,
                new TossPaymentsProperties(), membershipService);
        when(membershipService.getMyPlan(USER_ID)).thenReturn(myPlan("ENTERPRISE"));
    }

    private PaymentConfirmRequest request() {
        return new PaymentConfirmRequest(PAYMENT_KEY, ORDER_ID, AMOUNT);
    }

    @Test
    void 금액위변조로_검증에_실패하면_PG를_호출하지않는다() {
        when(paymentWriter.prepareConfirm(eq(USER_ID), any()))
                .thenThrow(new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH));

        assertThatThrownBy(() -> service.confirm(USER_ID, request()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_AMOUNT_MISMATCH));

        verify(tossPaymentsClient, never()).confirm(anyString(), anyString(), anyLong());
        verify(paymentWriter, never()).markApproved(any(), any());
        verify(paymentWriter, never()).applyPlanTransition(any());
    }

    @Test
    void 이미_승인된_주문_재요청은_PG재호출없이_현재플랜을_반환한다() {
        when(paymentWriter.prepareConfirm(eq(USER_ID), any()))
                .thenReturn(PaymentConfirmPreparation.alreadyPaid(PAYMENT_ID, false));

        MyPlanResponse response = service.confirm(USER_ID, request());

        assertThat(response.plan().name()).isEqualTo("ENTERPRISE");
        verify(tossPaymentsClient, never()).confirm(anyString(), anyString(), anyLong());
        verify(paymentWriter, never()).applyPlanTransition(any());
    }

    @Test
    void 승인후_플랜미반영_주문의_재요청은_PG없이_전이만_재시도한다() {
        // 자가 복구 경로 — 승인 기록은 이미 커밋됐으니 돈을 다시 움직이지 않고 엔타이틀먼트만 맞춘다.
        when(paymentWriter.prepareConfirm(eq(USER_ID), any()))
                .thenReturn(PaymentConfirmPreparation.alreadyPaid(PAYMENT_ID, true));

        service.confirm(USER_ID, request());

        verify(tossPaymentsClient, never()).confirm(anyString(), anyString(), anyLong());
        verify(paymentWriter).applyPlanTransition(PAYMENT_ID);
    }

    @Test
    void 게이트웨이_실패시_FAILED를_기록하고_플랜은_그대로다() {
        when(paymentWriter.prepareConfirm(eq(USER_ID), any()))
                .thenReturn(PaymentConfirmPreparation.readyToApprove(PAYMENT_ID, AMOUNT));
        when(tossPaymentsClient.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .thenThrow(new TossPaymentApprovalException("REJECT_CARD_COMPANY", "카드사 승인 거절"));

        assertThatThrownBy(() -> service.confirm(USER_ID, request()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_GATEWAY_ERROR));

        verify(paymentWriter).markFailed(PAYMENT_ID, "REJECT_CARD_COMPANY", "카드사 승인 거절");
        // 보안 요구 5 — 승인 성공 후에만 전이한다.
        verify(paymentWriter, never()).markApproved(any(), any());
        verify(paymentWriter, never()).applyPlanTransition(any());
    }

    @Test
    void 타임아웃_등_통신실패도_플랜을_바꾸지않는다() {
        when(paymentWriter.prepareConfirm(eq(USER_ID), any()))
                .thenReturn(PaymentConfirmPreparation.readyToApprove(PAYMENT_ID, AMOUNT));
        when(tossPaymentsClient.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .thenThrow(new TossPaymentApprovalException(
                        TossPaymentApprovalException.CODE_UNREACHABLE, "결제 서버에 연결하지 못했습니다."));

        assertThatThrownBy(() -> service.confirm(USER_ID, request()))
                .isInstanceOf(BusinessException.class);

        verify(paymentWriter).markFailed(eq(PAYMENT_ID),
                eq(TossPaymentApprovalException.CODE_UNREACHABLE), anyString());
        verify(paymentWriter, never()).applyPlanTransition(any());
    }

    @Test
    void 승인성공시_승인기록_후_플랜전이_순서로_반영한다() {
        TossPaymentApproval approval = new TossPaymentApproval(
                PAYMENT_KEY, PaymentMethod.CARD, "https://receipt", Instant.now());
        when(paymentWriter.prepareConfirm(eq(USER_ID), any()))
                .thenReturn(PaymentConfirmPreparation.readyToApprove(PAYMENT_ID, AMOUNT));
        when(tossPaymentsClient.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT)).thenReturn(approval);

        MyPlanResponse response = service.confirm(USER_ID, request());

        InOrder inOrder = Mockito.inOrder(paymentWriter);
        // 돈이 오간 사실을 먼저 커밋해야, 전이 실패가 승인 기록까지 롤백시키지 않는다.
        inOrder.verify(paymentWriter).markApproved(PAYMENT_ID, approval);
        inOrder.verify(paymentWriter).applyPlanTransition(PAYMENT_ID);
        assertThat(response.plan().name()).isEqualTo("ENTERPRISE");
    }

    @Test
    void PG에는_요청금액이_아니라_서버가_사전등록한_금액을_보낸다() {
        // 보안 요구 1·2 — 대조를 통과한 뒤에도 승인 호출에 쓰는 값은 서버 저장값이다.
        when(paymentWriter.prepareConfirm(eq(USER_ID), any()))
                .thenReturn(PaymentConfirmPreparation.readyToApprove(PAYMENT_ID, AMOUNT));
        when(tossPaymentsClient.confirm(anyString(), anyString(), anyLong()))
                .thenReturn(new TossPaymentApproval(PAYMENT_KEY, PaymentMethod.CARD, null, Instant.now()));

        service.confirm(USER_ID, request());

        verify(tossPaymentsClient).confirm(PAYMENT_KEY, ORDER_ID, AMOUNT);
    }

    private static MyPlanResponse myPlan(String planName) {
        return new MyPlanResponse(
                new MyPlanResponse.PlanInfo(planName, null, "ACTIVE", null, null),
                new MyPlanResponse.Limits(null, null, null),
                new MyPlanResponse.Usage(0, 0, 0, LocalDate.of(2026, 7, 1)));
    }
}
