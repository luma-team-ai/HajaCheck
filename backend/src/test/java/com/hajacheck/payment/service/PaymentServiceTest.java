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
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.payment.dto.PaymentConfirmRequest;
import com.hajacheck.payment.dto.PaymentOrderRequest;
import com.hajacheck.payment.dto.PaymentOrderResponse;
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
import org.springframework.dao.DataIntegrityViolationException;
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
                .thenThrow(TossPaymentApprovalException.rejected("REJECT_CARD_COMPANY", "카드사 승인 거절"));

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
    void 타임아웃_등_결과불명은_FAILED로_닫지않고_READY로_남긴다() {
        // 리뷰 P1-C — 토스에서 승인이 성사된 뒤 응답만 못 받았을 수 있다. 여기서 FAILED 로 확정하면
        // isConfirmable()=false 가 되어 같은 orderId 재확정이 404 로 영구 차단되고, 돈은 나갔는데
        // 플랜은 없는 상태가 복구 불가로 굳는다.
        when(paymentWriter.prepareConfirm(eq(USER_ID), any()))
                .thenReturn(PaymentConfirmPreparation.readyToApprove(PAYMENT_ID, AMOUNT));
        when(tossPaymentsClient.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .thenThrow(TossPaymentApprovalException.outcomeUnknown(
                        TossPaymentApprovalException.CODE_UNREACHABLE, "결제 서버에 연결하지 못했습니다."));

        assertThatThrownBy(() -> service.confirm(USER_ID, request()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_GATEWAY_ERROR));

        verify(paymentWriter, never()).markFailed(any(), anyString(), anyString());
        verify(paymentWriter, never()).applyPlanTransition(any());
    }

    @Test
    void PG_5xx에서_온_결과불명도_FAILED로_닫지않는다() {
        // ⚠️ 리뷰 P1 잔존분 — 클라이언트가 5xx 를 outcomeUnknown 으로 분류하므로(TossPaymentsClientTest),
        // 서비스도 그것을 확정 거절과 구분해 주문을 닫지 않아야 한다.
        when(paymentWriter.prepareConfirm(eq(USER_ID), any()))
                .thenReturn(PaymentConfirmPreparation.readyToApprove(PAYMENT_ID, AMOUNT));
        when(tossPaymentsClient.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .thenThrow(TossPaymentApprovalException.outcomeUnknown(
                        "FAILED_INTERNAL_SYSTEM_PROCESSING", "일시적인 오류입니다."));

        assertThatThrownBy(() -> service.confirm(USER_ID, request()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_GATEWAY_ERROR));

        verify(paymentWriter, never()).markFailed(any(), anyString(), anyString());
    }

    @Test
    void 만료_시도상한_주문은_취소를_먼저_커밋한_뒤_404를_던진다() {
        // 리뷰 P2 — 검증 트랜잭션 안에서 쓰고 던지면 롤백된다. 닫는 쓰기는 별도 트랜잭션으로 분리했다.
        when(paymentWriter.prepareConfirm(eq(USER_ID), any()))
                .thenReturn(PaymentConfirmPreparation.expired(PAYMENT_ID));

        assertThatThrownBy(() -> service.confirm(USER_ID, request()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_ORDER_NOT_FOUND));

        verify(paymentWriter).closeUnusableOrder(PAYMENT_ID, PaymentConfirmOutcome.EXPIRED);
        verify(tossPaymentsClient, never()).confirm(anyString(), anyString(), anyLong());
    }

    @Test
    void 승인시도_상한초과도_취소_커밋_후_404다() {
        when(paymentWriter.prepareConfirm(eq(USER_ID), any()))
                .thenReturn(PaymentConfirmPreparation.attemptLimitExceeded(PAYMENT_ID));

        assertThatThrownBy(() -> service.confirm(USER_ID, request()))
                .isInstanceOf(BusinessException.class);

        verify(paymentWriter)
                .closeUnusableOrder(PAYMENT_ID, PaymentConfirmOutcome.ATTEMPT_LIMIT_EXCEEDED);
        verify(tossPaymentsClient, never()).confirm(anyString(), anyString(), anyLong());
    }

    @Test
    void PG_호출_직전에_시도횟수를_먼저_기록한다() {
        // 보안 리뷰 P2 — 호출이 어떻게 끝나든 시도 횟수가 남아야 상한이 의미를 갖는다.
        when(paymentWriter.prepareConfirm(eq(USER_ID), any()))
                .thenReturn(PaymentConfirmPreparation.readyToApprove(PAYMENT_ID, AMOUNT));
        when(tossPaymentsClient.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .thenThrow(TossPaymentApprovalException.outcomeUnknown(
                        TossPaymentApprovalException.CODE_UNREACHABLE, "연결 실패"));

        assertThatThrownBy(() -> service.confirm(USER_ID, request()))
                .isInstanceOf(BusinessException.class);

        InOrder inOrder = Mockito.inOrder(paymentWriter, tossPaymentsClient);
        inOrder.verify(paymentWriter).recordConfirmAttempt(PAYMENT_ID);
        inOrder.verify(tossPaymentsClient).confirm(PAYMENT_KEY, ORDER_ID, AMOUNT);
    }

    @Test
    void 전이중_일반런타임예외도_결제완료_반영대기로_수렴한다() {
        // ⚠️ 리뷰 P2 — catch(BusinessException)만 있으면 DataAccessException 같은 일반 런타임 예외가
        // 그대로 전파돼 confirm 이 500 이 된다. 이 시점은 이미 PG 청구 + markApproved(PAID) 커밋 이후라,
        // 사용자가 실패로 오인해 재결제하면 이중 청구가 난다.
        TossPaymentApproval approval = new TossPaymentApproval(
                PAYMENT_KEY, PaymentMethod.CARD, "https://receipt", Instant.now());
        when(paymentWriter.prepareConfirm(eq(USER_ID), any()))
                .thenReturn(PaymentConfirmPreparation.readyToApprove(PAYMENT_ID, AMOUNT));
        when(tossPaymentsClient.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT)).thenReturn(approval);
        Mockito.doThrow(new DataIntegrityViolationException("usage carry-over failed"))
                .when(paymentWriter).applyPlanTransition(PAYMENT_ID);

        assertThatThrownBy(() -> service.confirm(USER_ID, request()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_PLAN_APPLY_PENDING));

        // 승인 기록은 보존된다(돈이 오간 사실은 별도 트랜잭션으로 이미 커밋됐다).
        verify(paymentWriter).markApproved(PAYMENT_ID, approval);
    }

    @Test
    void 주문생성_동시경합에_지면_1회_재시도해_기존주문을_돌려준다() {
        // 리뷰 P2 — 부분 유니크 인덱스가 경합을 직렬화하고, 진 쪽은 재시도로 이긴 쪽 주문을 재사용한다.
        PaymentOrderResponse existing = new PaymentOrderResponse(
                ORDER_ID, "ENTERPRISE", AMOUNT, "HajaCheck ENTERPRISE 플랜 구독");
        when(paymentWriter.createOrder(USER_ID, PlanName.ENTERPRISE))
                .thenThrow(new DataIntegrityViolationException("uq_payments_ready_user violated"))
                .thenReturn(existing);

        PaymentOrderResponse response =
                service.createOrder(USER_ID, new PaymentOrderRequest(PlanName.ENTERPRISE));

        assertThat(response.orderId()).isEqualTo(ORDER_ID);
        verify(paymentWriter, org.mockito.Mockito.times(2)).createOrder(USER_ID, PlanName.ENTERPRISE);
    }

    @Test
    void 주문생성_재시도까지_충돌하면_500이_아니라_409다() {
        when(paymentWriter.createOrder(USER_ID, PlanName.ENTERPRISE))
                .thenThrow(new DataIntegrityViolationException("uq_payments_ready_user violated"));

        assertThatThrownBy(() -> service.createOrder(USER_ID, new PaymentOrderRequest(PlanName.ENTERPRISE)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLAN_ACTIVE_SUBSCRIPTION_CONFLICT));
    }

    @Test
    void 미승인결제_전이시도는_결제완료로_포장하지않는다() {
        // 리뷰 P3 — PAYMENT_GATEWAY_ERROR(미승인 전이 방어선)까지 PENDING 으로 바꾸면 승인되지도 않은
        // 결제를 "결제 정상 완료"로 안내하게 된다.
        TossPaymentApproval approval = new TossPaymentApproval(
                PAYMENT_KEY, PaymentMethod.CARD, "https://receipt", Instant.now());
        when(paymentWriter.prepareConfirm(eq(USER_ID), any()))
                .thenReturn(PaymentConfirmPreparation.readyToApprove(PAYMENT_ID, AMOUNT));
        when(tossPaymentsClient.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT)).thenReturn(approval);
        Mockito.doThrow(new BusinessException(ErrorCode.PAYMENT_GATEWAY_ERROR))
                .when(paymentWriter).applyPlanTransition(PAYMENT_ID);

        assertThatThrownBy(() -> service.confirm(USER_ID, request()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_GATEWAY_ERROR));
    }

    @Test
    void 응답해석불가도_결과불명이라_FAILED로_닫지않는다() {
        when(paymentWriter.prepareConfirm(eq(USER_ID), any()))
                .thenReturn(PaymentConfirmPreparation.readyToApprove(PAYMENT_ID, AMOUNT));
        when(tossPaymentsClient.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .thenThrow(TossPaymentApprovalException.outcomeUnknown(
                        TossPaymentApprovalException.CODE_INVALID_RESPONSE, "결제 서버 응답을 처리할 수 없습니다."));

        assertThatThrownBy(() -> service.confirm(USER_ID, request()))
                .isInstanceOf(BusinessException.class);

        verify(paymentWriter, never()).markFailed(any(), anyString(), anyString());
    }

    @Test
    void 결과불명_이후_재확정하면_원장이_READY라_승인을_다시_시도할수있다() {
        // 위 시나리오의 후속 — 주문이 닫히지 않았으므로 재확정 요청이 prepareConfirm 을 통과해 PG 를
        // 다시 호출하고, 이번엔 성공해 플랜이 반영된다(P1-C 가 지키려는 복구 경로 전체).
        TossPaymentApproval approval = new TossPaymentApproval(
                PAYMENT_KEY, PaymentMethod.CARD, "https://receipt", Instant.now());
        when(paymentWriter.prepareConfirm(eq(USER_ID), any()))
                .thenReturn(PaymentConfirmPreparation.readyToApprove(PAYMENT_ID, AMOUNT));
        when(tossPaymentsClient.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .thenThrow(TossPaymentApprovalException.outcomeUnknown(
                        TossPaymentApprovalException.CODE_UNREACHABLE, "결제 서버에 연결하지 못했습니다."))
                .thenReturn(approval);

        assertThatThrownBy(() -> service.confirm(USER_ID, request()))
                .isInstanceOf(BusinessException.class);
        MyPlanResponse response = service.confirm(USER_ID, request());

        assertThat(response.plan().name()).isEqualTo("ENTERPRISE");
        verify(paymentWriter).markApproved(PAYMENT_ID, approval);
        verify(paymentWriter).applyPlanTransition(PAYMENT_ID);
    }

    @Test
    void 이미처리된결제_응답이면_원장을_재조회해_멱등성공으로_응답한다() {
        // 리뷰 P2 — 같은 주문에 confirm 두 건이 동시에 도착하면 둘 다 PG 를 호출할 수 있고, 진 쪽이
        // "이미 처리된 결제"를 받는다. 그대로 502 를 주면 결제 성공인데 사용자는 실패로 읽고 재결제한다.
        when(paymentWriter.prepareConfirm(eq(USER_ID), any()))
                .thenReturn(PaymentConfirmPreparation.readyToApprove(PAYMENT_ID, AMOUNT));
        when(tossPaymentsClient.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .thenThrow(TossPaymentApprovalException.rejected(
                        TossPaymentApprovalException.CODE_ALREADY_PROCESSED, "이미 처리된 결제입니다."));
        when(paymentWriter.resolveSettlementState(PAYMENT_ID))
                .thenReturn(new PaymentSettlementState(true, false));

        MyPlanResponse response = service.confirm(USER_ID, request());

        assertThat(response.plan().name()).isEqualTo("ENTERPRISE");
        verify(paymentWriter, never()).markFailed(any(), anyString(), anyString());
    }

    @Test
    void 이미처리된결제_인데_원장은_미승인이면_닫지않고_502로_알린다() {
        when(paymentWriter.prepareConfirm(eq(USER_ID), any()))
                .thenReturn(PaymentConfirmPreparation.readyToApprove(PAYMENT_ID, AMOUNT));
        when(tossPaymentsClient.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .thenThrow(TossPaymentApprovalException.rejected(
                        TossPaymentApprovalException.CODE_ALREADY_PROCESSED, "이미 처리된 결제입니다."));
        when(paymentWriter.resolveSettlementState(PAYMENT_ID))
                .thenReturn(new PaymentSettlementState(false, false));

        assertThatThrownBy(() -> service.confirm(USER_ID, request()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_GATEWAY_ERROR));
        verify(paymentWriter, never()).markFailed(any(), anyString(), anyString());
    }

    @Test
    void 승인후_플랜반영이_실패하면_결제완료_반영대기로_알린다() {
        // 리뷰 P2 — 403/500 을 그대로 주면 사용자가 "결제 실패"로 읽고 재결제해 중복 청구가 난다.
        TossPaymentApproval approval = new TossPaymentApproval(
                PAYMENT_KEY, PaymentMethod.CARD, "https://receipt", Instant.now());
        when(paymentWriter.prepareConfirm(eq(USER_ID), any()))
                .thenReturn(PaymentConfirmPreparation.readyToApprove(PAYMENT_ID, AMOUNT));
        when(tossPaymentsClient.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT)).thenReturn(approval);
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.PAYMENT_FORBIDDEN))
                .when(paymentWriter).applyPlanTransition(PAYMENT_ID);

        assertThatThrownBy(() -> service.confirm(USER_ID, request()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_PLAN_APPLY_PENDING));

        // 승인 기록 자체는 남아 있어야 한다(돈이 오간 사실은 롤백되지 않는다).
        verify(paymentWriter).markApproved(PAYMENT_ID, approval);
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
