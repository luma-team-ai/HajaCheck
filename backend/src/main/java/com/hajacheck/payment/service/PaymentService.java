package com.hajacheck.payment.service;

import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.dto.MyPlanResponse;
import com.hajacheck.membership.service.MembershipService;
import com.hajacheck.payment.config.TossPaymentsProperties;
import com.hajacheck.payment.dto.PaymentConfirmRequest;
import com.hajacheck.payment.dto.PaymentHistoryResponse;
import com.hajacheck.payment.dto.PaymentOrderRequest;
import com.hajacheck.payment.dto.PaymentOrderResponse;
import com.hajacheck.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 토스페이먼츠 샌드박스 결제(#988 / HAJA-489) — 주문 사전 등록 → 결제창(FE) → 승인 → 플랜 전이.
 *
 * <p><b>이 클래스에는 {@code @Transactional} 이 없다.</b> 의도적이다: {@link #confirm} 이 트랜잭션 밖에서
 * PG 를 호출하고, DB 쓰기는 전부 {@link PaymentWriter} 의 짧은 트랜잭션들에 위임한다. 클래스에
 * {@code @Transactional(readOnly = true)} 를 달면 PG 호출 구간까지 트랜잭션이 열린 채 커넥션을 붙잡는다
 * (경계 설계의 근거 전문은 {@link PaymentWriter} javadoc 참고).
 *
 * <p><b>범위 밖</b>: 웹훅 수신, 환불·부분취소, 정기결제(빌링키), 정산, 간편결제·계좌이체.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentWriter paymentWriter;
    private final PaymentRepository paymentRepository;
    private final TossPaymentsClient tossPaymentsClient;
    private final TossPaymentsProperties tossPaymentsProperties;
    private final MembershipService membershipService;

    /**
     * 주문 사전 등록 — 서버가 orderId·금액을 확정한다(보안 요구 1·2). 검증·저장은 단일 트랜잭션.
     *
     * <p><b>동시 주문 생성 경합 처리</b>(리뷰 P2): 중복 주문 방지는 "기존 READY 조회 → 없으면 INSERT"라
     * 그 자체로는 원자적이지 않다. 그래서 DB 에 부분 유니크 인덱스({@code uq_payments_ready_company}/
     * {@code uq_payments_ready_user})를 두어 경합을 직렬화하는데, 진 쪽은
     * {@link DataIntegrityViolationException} 을 받고 그 트랜잭션은 롤백된다.
     *
     * <p>진 쪽은 <b>여기서 한 번만 재시도</b>한다 — 그 시점엔 이긴 쪽 주문이 이미 커밋돼 있으므로, 재시도가
     * 재사용 분기를 타 <b>같은 주문을 그대로</b> 돌려준다(사용자에겐 경합이 보이지 않는다). 재시도까지
     * 실패하면 500 을 내보내지 않고 409 로 변환한다. 재시도는 트랜잭션 밖인 이 계층에서만 가능하다 —
     * {@code PaymentWriter} 안에서 잡으면 이미 롤백 전용으로 오염된 트랜잭션이라 재조회가 불가능하다.
     */
    public PaymentOrderResponse createOrder(Long userId, PaymentOrderRequest request) {
        try {
            return paymentWriter.createOrder(userId, request.planName());
        } catch (DataIntegrityViolationException e) {
            log.info("동시 결제 주문 생성 경합 — 기존 주문 재사용을 위해 1회 재시도(userId={})", userId);
        }
        try {
            return paymentWriter.createOrder(userId, request.planName());
        } catch (DataIntegrityViolationException e) {
            // 재시도에서도 충돌 = 그 사이 또 다른 경합. 사용자에게 500 을 내보내지 않는다.
            log.warn("결제 주문 생성 재시도 실패(userId={}) — 상태 충돌로 응답", userId);
            throw new BusinessException(ErrorCode.PLAN_ACTIVE_SUBSCRIPTION_CONFLICT);
        }
    }

    /**
     * 결제 승인 — 성공하면 갱신된 플랜을 {@code GET /api/me/plan} 과 <b>동일 스키마</b>로 돌려준다(계약).
     *
     * <p>흐름
     * <ol>
     *   <li><b>검증</b>({@code prepareConfirm}): 주문 존재·소유자·유효시간·상태·금액 대조 + 전이 가드
     *       (동일 플랜 재결제 차단 포함). 하나라도 어긋나면 <b>PG 를 호출하지 않는다</b>(보안 요구 2).</li>
     *   <li><b>멱등 분기</b>(보안 요구 3): 이미 PAID 인 orderId 면 PG 를 다시 호출하지 않고 200 으로 현재
     *       상태를 돌려준다. 이때 플랜 전이가 남아 있으면(PAID 인데 user_plan_id 가 비어 있음) 승인 재호출
     *       없이 <b>전이만</b> 재시도한다(일시적 실패에 한해 자가 복구 — 결정적 원인은 대사 #1010 대상).</li>
     *   <li><b>승인</b>(트랜잭션 밖): 실패는 <b>세 갈래</b>로 갈린다 — 확정 거절만 FAILED 로 닫고, 결과
     *       불명은 READY 로 남기며, "이미 처리된 결제"는 원장을 재조회해 멱등 성공으로 흡수한다
     *       ({@link #handleApprovalFailure}). 어느 경우든 승인 없이 플랜이 바뀌지는 않는다(보안 요구 5).</li>
     *   <li><b>반영</b>: 승인 기록 커밋 → 플랜 전이 커밋(사용량 이월 포함).</li>
     * </ol>
     */
    public MyPlanResponse confirm(Long userId, PaymentConfirmRequest request) {
        PaymentConfirmPreparation preparation = paymentWriter.prepareConfirm(userId, request);

        if (preparation.requiresClosing()) {
            // 만료·시도 상한 초과 — 취소 기록을 <b>먼저 커밋</b>한 뒤 404 를 던진다. 검증 트랜잭션 안에서
            // 쓰고 곧바로 예외를 던지면 그 UPDATE 가 함께 롤백돼 저장되지 않는다(리뷰 P2).
            paymentWriter.closeUnusableOrder(preparation.paymentId(), preparation.outcome());
            throw new BusinessException(ErrorCode.PAYMENT_ORDER_NOT_FOUND);
        }
        if (preparation.alreadyPaid()) {
            return settleAlreadyPaid(userId, request.orderId(), preparation.paymentId(),
                    preparation.planApplicationPending());
        }

        // 주문당 PG 호출 상한의 근거 — 호출 직전에 독립 트랜잭션으로 커밋해, 이후 호출이 어떻게 끝나든
        // 시도 횟수가 남게 한다(보안 리뷰 P2). 남지 않으면 "결과 불명 → READY 유지" 정책이 무한 반복을 연다.
        paymentWriter.recordConfirmAttempt(preparation.paymentId());

        TossPaymentApproval approval;
        try {
            // ⚠️ 트랜잭션 밖 — 서버가 사전 등록한 금액(preparation.amount())만 넘긴다(요청값 아님).
            approval = tossPaymentsClient.confirm(
                    request.paymentKey(), request.orderId(), preparation.amount());
        } catch (TossPaymentApprovalException e) {
            return handleApprovalFailure(userId, request.orderId(), preparation.paymentId(), e);
        }

        paymentWriter.markApproved(preparation.paymentId(), approval);
        applyPlanTransitionOrPending(preparation.paymentId(), request.orderId());
        return membershipService.getMyPlan(userId);
    }

    /**
     * 이미 승인된 주문의 재확정 — PG 를 다시 호출하지 않는다(보안 요구 3). 전이가 남아 있으면 그것만
     * 재시도한다. <b>자가 복구는 일시적 실패에 한한다</b> — 소속 변경·owner 교체·활성 구독 부재 같은
     * 결정적 원인은 재요청해도 동일하게 실패하며, 그때는 PAYMENT_PLAN_APPLY_PENDING 으로 안내하고
     * 대사(#1010)로 처리한다.
     */
    private MyPlanResponse settleAlreadyPaid(Long userId, String orderId, Long paymentId,
                                             boolean planApplicationPending) {
        if (planApplicationPending) {
            log.warn("승인 완료 후 플랜 미반영 주문 재확정 — 전이만 재시도(orderId={})", orderId);
            applyPlanTransitionOrPending(paymentId, orderId);
        } else {
            log.info("이미 승인된 주문 재확정 — PG 재호출 없이 현재 상태 반환(orderId={})", orderId);
        }
        return membershipService.getMyPlan(userId);
    }

    /**
     * PG 승인 실패 처리 — <b>"확정 거절"·"결과 불명"·"이미 처리됨"을 구분한다</b>(리뷰 P1-C / P2).
     *
     * <ul>
     *   <li><b>이미 처리됨</b>: 같은 주문에 confirm 두 건이 동시에 도착해 진 쪽이 받는 응답이다. 원장을
     *       다시 읽어 PAID 면 <b>결제는 성공한 것</b>이므로 200 으로 현재 플랜을 돌려준다 — 여기서 502 를
     *       주면 사용자가 실패로 읽고 재결제해 중복 청구가 난다.</li>
     *   <li><b>결과 불명</b>(타임아웃·연결 실패·응답 해석 불가): <b>FAILED 로 닫지 않는다.</b> 토스에서
     *       승인이 성사된 뒤 응답만 못 받았을 수 있는데, 여기서 확정해 버리면 {@code isConfirmable()=false}
     *       가 되어 같은 orderId 재확정이 404 로 <b>영구 차단</b>된다 — 돈은 나갔는데 플랜은 없고 복구도
     *       불가한 상태로 굳는다. READY 로 남겨 두면 사용자가 다시 시도할 수 있고, 그때 PG 가 "이미 처리된
     *       결제"로 답하면 위 분기가 성공으로 흡수한다.</li>
     *   <li><b>확정 거절</b>(PG 가 실패 코드를 명시): 승인이 일어나지 않았음이 확실하므로 FAILED 로 기록한다.</li>
     * </ul>
     */
    private MyPlanResponse handleApprovalFailure(Long userId, String orderId, Long paymentId,
                                                 TossPaymentApprovalException e) {
        if (e.isAlreadyProcessed()) {
            PaymentSettlementState state = paymentWriter.resolveSettlementState(paymentId);
            if (state.paid()) {
                log.info("동시 승인 경합 — 다른 요청이 이미 승인 완료(orderId={}), 멱등 성공으로 응답", orderId);
                return settleAlreadyPaid(userId, orderId, paymentId, state.planApplicationPending());
            }
            // PG 는 처리됐다는데 우리 원장은 아직 미승인 — 결과 불명과 동일하게 다룬다(닫지 않는다).
            log.error("PG는 승인됐다고 하나 원장은 미승인 상태 — orderId={} (수동 대사 필요)", orderId);
            throw new BusinessException(ErrorCode.PAYMENT_GATEWAY_ERROR);
        }

        if (e.isOutcomeUnknown()) {
            log.warn("결제 승인 결과 불명(orderId={}, code={}) — 주문을 READY 로 유지해 재확정 여지를 남긴다",
                    orderId, e.getCode());
            throw new BusinessException(ErrorCode.PAYMENT_GATEWAY_ERROR);
        }

        paymentWriter.markFailed(paymentId, e.getCode(), e.getSafeMessage());
        throw new BusinessException(ErrorCode.PAYMENT_GATEWAY_ERROR);
    }

    /**
     * 승인 후 플랜 반영 — 실패하면 <b>"결제 완료, 반영 처리 중"</b>으로 바꿔 던진다(리뷰 P2).
     *
     * <p>이 단계의 실패는 이미 돈이 나간 뒤에 일어난다. 원래 예외(403 PAYMENT_FORBIDDEN·409 경합 등)를
     * 그대로 돌려주면 사용자는 "결제가 실패했다"고 읽고 <b>다시 결제</b>해 중복 청구로 이어진다. 반드시
     * "결제는 됐고 반영만 남았다"가 전달돼야 한다. 원인 코드는 응답이 아니라 서버 로그로 남긴다.
     *
     * <p>⚠️ <b>{@link ErrorCode#PAYMENT_GATEWAY_ERROR} 만은 포장하지 않는다</b>(리뷰 P3). 그 코드는
     * {@code PaymentWriter#applyPlanTransition} 의 "미승인 결제로는 전이하지 않는다" 방어선이 쓰는
     * 값이라, 포장해 버리면 <b>승인되지도 않은 결제를 "결제 정상 완료"로 안내</b>하게 된다 — 정확히
     * 반대되는 거짓말이다. 그 경우는 원래 의미(게이트웨이 오류) 그대로 올린다.
     *
     * <p>또한 "재요청하면 자가 복구된다"는 <b>일시적 실패에 한한다</b> — 소속 변경·owner 교체·활성 구독
     * 부재처럼 결정적인 원인은 재요청해도 같은 결과라, 대사(#1010)로 처리해야 한다.
     */
    private void applyPlanTransitionOrPending(Long paymentId, String orderId) {
        try {
            paymentWriter.applyPlanTransition(paymentId);
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.PAYMENT_GATEWAY_ERROR) {
                // 미승인 결제 전이 시도 — "결제 완료"로 안내하면 안 된다. 원래 의미로 올린다.
                log.error("미승인 결제에 대한 플랜 반영 시도 — orderId={} (도달 불가 경로)", orderId);
                throw e;
            }
            log.error("결제 승인 후 플랜 반영 실패 — orderId={} code={} (PAID + user_plan_id null 상태로 남는다)",
                    orderId, e.getErrorCode());
            throw new BusinessException(ErrorCode.PAYMENT_PLAN_APPLY_PENDING);
        } catch (RuntimeException e) {
            // ⚠️ BusinessException 만 잡으면 안 된다(리뷰 P2). 전이 경로(만료 UPDATE·사용량 이월 등)에서
            // DataAccessException 같은 일반 런타임 예외가 나면 그대로 전파돼 confirm 이 500 이 되는데,
            // 이 시점은 이미 PG 청구 + markApproved(PAID) 커밋 이후다. 사용자가 실패로 오인해 재결제하면
            // 이중 청구가 나므로, PAYMENT_PLAN_APPLY_PENDING 이 막으려던 시나리오가 그대로 뚫린다.
            // 원인은 응답이 아니라 로그로만 남긴다(스택 포함 — 여기 예외엔 시크릿이 실리지 않는다).
            log.error("결제 승인 후 플랜 반영 중 예기치 못한 오류 — orderId={} cause={} "
                    + "(PAID + user_plan_id null 상태로 남는다)", orderId, e.getClass().getSimpleName(), e);
            throw new BusinessException(ErrorCode.PAYMENT_PLAN_APPLY_PENDING);
        }
    }

    /**
     * 결제 이력 조회(#864) — 요청자 본인이 만든 주문만, 최신순. 회사 구독 결제도 주문 소유자는 회사
     * owner 이므로 별도 회사 축을 두지 않는다({@code PaymentRepository} javadoc 참고).
     */
    @Transactional(readOnly = true)
    public PaymentHistoryResponse getMyPayments(Long userId) {
        return PaymentHistoryResponse.from(paymentRepository.findByUserIdOrderByRequestedAtDescIdDesc(
                userId, PageRequest.of(0, tossPaymentsProperties.getHistoryMaxSize())));
    }
}
