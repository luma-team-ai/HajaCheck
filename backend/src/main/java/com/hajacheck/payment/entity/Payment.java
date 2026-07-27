package com.hajacheck.payment.entity;

import com.hajacheck.membership.entity.PlanName;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 플랜 구독 결제 원장 — DDL payments 테이블 대응(#988 / HAJA-489, V19).
 *
 * <p><b>왜 주문을 사전 등록하는가</b>: 결제창을 띄우기 전에 서버가 {@code orderId}·{@code amount} 를
 * 확정해 READY 로 저장해 두고, 승인 단계에서는 클라이언트가 보낸 금액을 <b>저장된 값과 대조만</b> 한다.
 * 이 사전 등록이 없으면 청구 금액의 유일한 출처가 클라이언트 요청이 되어 위변조를 막을 수 없다.
 *
 * <p><b>스냅샷</b>: {@code planName}·{@code amount} 는 결제 시점 값을 복사해 둔다. 플랫폼 관리자가 나중에
 * 요금제 가격을 바꿔도 과거 결제 이력의 청구액이 소급 변경되면 안 되기 때문이다(plans 를 조인해 표시하면
 * 그렇게 된다).
 *
 * <p>상태 전이는 전부 이 엔티티의 메서드({@link #markPaid}/{@link #markFailed}/{@link #linkUserPlan})로만
 * 수행한다(세터 없음). created_at/updated_at 컬럼이 DDL 에 없어 BaseTimeEntity 를 상속하지 않는다
 * ({@code requestedAt} 이 생성 시각 역할 — {@link com.hajacheck.membership.entity.UserPlan} 과 동일).
 *
 * <p>enum 두 개는 PG named enum({@code payment_status_type}/{@code payment_method_type})이라
 * {@code @JdbcTypeCode(NAMED_ENUM)} + {@code columnDefinition} 으로 매핑한다(ddl-auto=validate 통과 —
 * UserPlan.status·Plan.name 과 동일 패턴).
 */
@Entity
@Getter
@Table(name = "payments", indexes = {
        @Index(name = "idx_payments_user", columnList = "user_id"),
        @Index(name = "idx_payments_company", columnList = "company_id")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "company_id")
    private Long companyId;

    @Column(name = "user_plan_id")
    private Long userPlanId;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "plan_name", columnDefinition = "plan_name_type", nullable = false)
    private PlanName planName;

    @Column(name = "amount", precision = 10, scale = 2, nullable = false)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "payment_status_type", nullable = false)
    private PaymentStatus status;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "payment_method_type")
    private PaymentMethod method;

    @Column(name = "payment_key", length = 200)
    private String paymentKey;

    @Column(name = "receipt_url")
    private String receiptUrl;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "failure_message")
    private String failureMessage;

    @Builder(access = AccessLevel.PRIVATE)
    private Payment(String orderId, Long userId, Long companyId, Long planId, PlanName planName,
                    BigDecimal amount) {
        this.orderId = orderId;
        this.userId = userId;
        this.companyId = companyId;
        this.planId = planId;
        this.planName = planName;
        this.amount = amount;
        this.status = PaymentStatus.READY;
        this.requestedAt = Instant.now();
    }

    /**
     * 주문 사전 등록(READY) 팩토리 — {@code orderId}·{@code amount} 는 <b>반드시 서버가 정한 값</b>이어야
     * 한다(클라이언트 전달값 금지). {@code userPlanId} 는 승인 성공 시점에 {@link #linkUserPlan} 으로 채운다.
     *
     * @param companyId 회사 구독 결제면 그 회사 id, 개인 구독이면 {@code null}
     */
    public static Payment createOrder(String orderId, Long userId, Long companyId, Long planId,
                                      PlanName planName, BigDecimal amount) {
        return Payment.builder()
                .orderId(orderId)
                .userId(userId)
                .companyId(companyId)
                .planId(planId)
                .planName(planName)
                .amount(amount)
                .build();
    }

    /** 이 주문의 소유자인지 — 인가 판정용(주문 소유자가 아니면 존재 여부를 알려주지 않고 403). */
    public boolean isOwnedBy(Long candidateUserId) {
        return this.userId != null && this.userId.equals(candidateUserId);
    }

    public boolean isPaid() {
        return this.status == PaymentStatus.PAID;
    }

    /** 승인 대기 상태(= 아직 확정 가능한 주문)인지. FAILED·CANCELED 는 재확정 대상이 아니다. */
    public boolean isConfirmable() {
        return this.status == PaymentStatus.READY;
    }

    /** 승인은 끝났는데 플랜 전이가 아직 반영되지 않은 상태 — 재확정 요청이 전이만 재시도할 근거이자 운영 대사 신호. */
    public boolean isPaidWithoutPlanApplied() {
        return isPaid() && this.userPlanId == null;
    }

    /**
     * PG 승인 성공 반영 — 멱등이다. 이미 PAID 면 아무것도 바꾸지 않는다(같은 orderId 동시 승인 경합에서
     * 뒤늦게 도착한 스레드가 앞선 승인 기록을 덮어쓰지 않게).
     *
     * @param method 매핑 불가한 수단이면 {@code null}(수단 표기 때문에 승인을 실패시키지 않는다)
     */
    public void markPaid(String paymentKey, PaymentMethod method, String receiptUrl, Instant approvedAt) {
        if (isPaid()) {
            return;
        }
        this.status = PaymentStatus.PAID;
        this.paymentKey = paymentKey;
        this.method = method;
        this.receiptUrl = receiptUrl;
        this.approvedAt = approvedAt == null ? Instant.now() : approvedAt;
        this.failureCode = null;
        this.failureMessage = null;
    }

    /**
     * PG 승인 실패 반영 — <b>READY 일 때만</b> 전이한다. 같은 orderId 로 두 요청이 동시에 승인을 시도하면
     * 한쪽은 성공(PAID)하고 다른 쪽은 "이미 처리된 결제" 오류를 받는데, 그 오류를 그대로 기록하면 방금
     * 성공한 승인이 FAILED 로 덮여 결제 원장이 거짓이 된다.
     */
    public void markFailed(String failureCode, String failureMessage) {
        if (!isConfirmable()) {
            return;
        }
        this.status = PaymentStatus.FAILED;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
    }

    /** 승인 결과로 발급·확정된 구독을 연결한다(PAID + userPlanId != null 이 최종 완료 상태). */
    public void linkUserPlan(Long userPlanId) {
        this.userPlanId = userPlanId;
    }
}
