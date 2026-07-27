package com.hajacheck.payment.repository;

import com.hajacheck.payment.entity.Payment;
import com.hajacheck.payment.entity.PaymentStatus;
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
     * 같은 <b>소유 주체·요금제</b>의 기존 READY 주문 조회(회사 구독 축) — 중복 주문 방지(리뷰 P1-B/P2).
     *
     * <p>⚠️ 조회 축이 DB 부분 유니크 인덱스({@code uq_payments_ready_company})와 <b>정확히 같아야 한다</b>.
     * 어긋나면 "조회로는 안 보이는데 INSERT 는 제약에 걸리는" 조합이 생겨 결제 경로가 잠긴다.
     *
     * <p>회사 구독 주문은 생성 인가가 회사 owner 한정이라 {@code user_id} 는 항상 그 owner 다. 다만 owner
     * 가 교체되면 전 owner 의 주문이 잡힐 수 있어, 호출부가 요청자 소유 여부를 한 번 더 확인한다.
     */
    Optional<Payment> findFirstByCompanyIdAndPlanIdAndStatus(
            Long companyId, Long planId, PaymentStatus status);

    /**
     * 같은 소유 주체·요금제의 기존 READY 주문 조회(개인 구독 축) — {@code company_id is null} 까지 조건에
     * 넣어 DB 부분 유니크 인덱스({@code uq_payments_ready_user})와 축을 일치시킨다.
     *
     * <p>개인 시절 주문과 기업 소속 이후 주문은 <b>서로 다른 인덱스</b>에 속하므로 충돌하지 않는다 —
     * 이전 구현처럼 (userId, planId) 만으로 묶으면 소속 전환 뒤 낡은 주문이 계속 잡혀 TTL 동안 결제가 잠겼다.
     */
    Optional<Payment> findFirstByUserIdAndPlanIdAndStatusAndCompanyIdIsNull(
            Long userId, Long planId, PaymentStatus status);


    /**
     * 결제 행을 <b>배타 잠금</b>으로 읽는다(호출 트랜잭션 커밋까지 유지). 승인 반영·실패 반영·플랜 전이 등
     * 결제 상태를 바꾸는 모든 단계가 이 메서드로 행을 잡는다.
     *
     * <p>이 잠금이 지키는 것은 <b>상태 전이 단계끼리의 직렬화</b>다. 같은 결제에 대한 두 전이가 겹쳐
     * 실행되면 뒤 트랜잭션이 앞의 승인 기록(paymentKey·영수증)을 덮어쓸 수 있는데, 잠금 덕분에 뒤
     * 트랜잭션은 앞의 커밋 결과를 보고 엔티티의 멱등 가드
     * ({@link com.hajacheck.payment.entity.Payment#markPaid}·{@code markFailed})로 빠진다.
     *
     * <p>⚠️ <b>PG 호출까지 직렬화하지는 못한다</b>(리뷰 P2 정정 — 이전 주석은 이 점을 잘못 설명했다).
     * 승인 전 검증({@code PaymentWriter#prepareConfirm})은 외부 호출을 트랜잭션 밖에 두려고 이 잠금을 잡지
     * 않으므로, 같은 주문에 동시에 도착한 confirm 두 건이 <b>모두</b> PG 를 호출할 수 있다. 그 경합은
     * ①PG 쪽 멱등(뒤늦은 쪽이 "이미 처리된 결제" 코드를 받음)과 ②호출부 처리
     * ({@code PaymentService#confirm} 의 alreadyProcessed 분기 — 원장을 재조회해 PAID 면 성공 응답)로
     * 흡수한다. 서로 다른 주문에 같은 {@code paymentKey} 가 붙는 경우는 이 잠금 범위 밖이라
     * {@code uq_payments_payment_key} 부분 유니크 인덱스가 최종 방어선이다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") Long id);
}
