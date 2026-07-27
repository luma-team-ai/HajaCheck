package com.hajacheck.payment.repository;

import com.hajacheck.payment.entity.Payment;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 결제 원장 저장소(#988 / HAJA-489).
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderId(String orderId);

    /**
     * 결제 이력 목록 — <b>요청자 본인이 만든 주문만</b> 돌려준다(계약: {@code GET /api/me/payments}).
     *
     * <p>회사 구독 결제도 주문 생성 인가가 회사 owner 한정이라 {@code user_id} 는 항상 그 owner 다. 즉
     * {@code company_id} 로 조회 범위를 넓히면 owner 가 아닌 회사 구성원까지 회사의 청구 이력(금액·영수증)을
     * 보게 되므로 그렇게 하지 않는다 — 조회 권한을 결제 권한보다 넓게 두지 않는다는 기존 원칙
     * ({@code AdminPlanService#previewChange} javadoc)과 같은 판단이다.
     */
    List<Payment> findByUserIdOrderByRequestedAtDescIdDesc(Long userId, Pageable pageable);

    /**
     * 승인 반영 대상 결제 행을 <b>배타 잠금</b>으로 읽는다(호출 트랜잭션 커밋까지 유지).
     *
     * <p>같은 {@code orderId} 로 동시에 도착한 두 승인 요청이 "READY 인지 확인 → PAID 로 전이"를 겹쳐
     * 실행하면 두 번째가 첫 번째의 승인 기록(paymentKey·영수증)을 덮어쓸 수 있다. 이 잠금이 두 트랜잭션을
     * 직렬화해 뒤 트랜잭션이 <b>앞 트랜잭션의 커밋 결과(PAID)를 보고</b> 멱등 분기로 빠지게 만든다.
     * 서로 다른 주문에 같은 {@code paymentKey} 가 붙는 경우는 이 잠금 범위 밖이라
     * {@code uq_payments_payment_key} 부분 유니크 인덱스가 최종 방어선이다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") Long id);
}
