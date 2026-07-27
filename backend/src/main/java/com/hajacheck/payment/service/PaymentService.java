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

    /** 주문 사전 등록 — 서버가 orderId·금액을 확정한다(보안 요구 1·2). 검증·저장은 단일 트랜잭션. */
    public PaymentOrderResponse createOrder(Long userId, PaymentOrderRequest request) {
        return paymentWriter.createOrder(userId, request.planName());
    }

    /**
     * 결제 승인 — 성공하면 갱신된 플랜을 {@code GET /api/me/plan} 과 <b>동일 스키마</b>로 돌려준다(계약).
     *
     * <p>흐름
     * <ol>
     *   <li><b>검증</b>({@code prepareConfirm}, 읽기 트랜잭션): 주문 존재·소유자·상태·금액 대조 + 전이 가드.
     *       하나라도 어긋나면 <b>PG 를 호출하지 않는다</b>(보안 요구 2).</li>
     *   <li><b>멱등 분기</b>(보안 요구 3): 이미 PAID 인 orderId 면 PG 를 다시 호출하지 않고 200 으로 현재
     *       상태를 돌려준다. 이때 플랜 전이가 남아 있으면(PAID 인데 user_plan_id 가 비어 있음) 승인 재호출
     *       없이 <b>전이만</b> 재시도해 스스로 복구한다.</li>
     *   <li><b>승인</b>(트랜잭션 밖): 실패하면 결제 원장에 FAILED 를 독립 커밋한 뒤 PAYMENT_GATEWAY_ERROR(502).
     *       이때 플랜은 그대로다(보안 요구 5).</li>
     *   <li><b>반영</b>: 승인 기록 커밋 → 플랜 전이 커밋(사용량 이월 포함).</li>
     * </ol>
     */
    public MyPlanResponse confirm(Long userId, PaymentConfirmRequest request) {
        PaymentConfirmPreparation preparation = paymentWriter.prepareConfirm(userId, request);

        if (preparation.alreadyPaid()) {
            if (preparation.planApplicationPending()) {
                // 승인은 끝났는데 전이가 남은 상태 — PG 재호출 없이 전이만 재시도(자가 복구).
                log.warn("승인 완료 후 플랜 미반영 주문 재확정 — 전이만 재시도(orderId={})", request.orderId());
                paymentWriter.applyPlanTransition(preparation.paymentId());
            } else {
                log.info("이미 승인된 주문 재확정 — PG 재호출 없이 현재 상태 반환(orderId={})", request.orderId());
            }
            return membershipService.getMyPlan(userId);
        }

        TossPaymentApproval approval;
        try {
            // ⚠️ 트랜잭션 밖 — 서버가 사전 등록한 금액(preparation.amount())만 넘긴다(요청값 아님).
            approval = tossPaymentsClient.confirm(
                    request.paymentKey(), request.orderId(), preparation.amount());
        } catch (TossPaymentApprovalException e) {
            paymentWriter.markFailed(preparation.paymentId(), e.getCode(), e.getSafeMessage());
            throw new BusinessException(ErrorCode.PAYMENT_GATEWAY_ERROR);
        }

        paymentWriter.markApproved(preparation.paymentId(), approval);
        paymentWriter.applyPlanTransition(preparation.paymentId());
        return membershipService.getMyPlan(userId);
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
