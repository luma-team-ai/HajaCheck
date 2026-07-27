package com.hajacheck.payment.repository;

import com.hajacheck.payment.entity.Payment;
import com.hajacheck.payment.entity.PaymentStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
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
     * 재사용 가능한 READY 주문 조회(리뷰 P1-B) — 같은 사용자가 같은 요금제로 결제창을 여러 번 열어도
     * 주문이 계속 쌓이지 않게, 아직 유효한 기존 주문을 그대로 돌려준다.
     *
     * <p>이 재사용이 없으면 READY 주문 2건을 만든 뒤 순차로 결제해 <b>2회 청구 + 구독 변화 0</b>을 만들 수
     * 있다(환불이 범위 밖이라 회수 수단이 없다). 승인 단계의 동일 플랜 차단
     * ({@code PaymentWriter#prepareConfirm})과 <b>짝을 이루는 근본 원인 차단</b>이다.
     *
     * <p>{@code companyId} 를 조건에 넣지 않는 이유: 주문의 companyId 는 생성 시점 사용자의 소속에서
     * 파생된 값이라 (userId, planId) 만으로 소유 주체가 특정된다. 소속이 바뀐 뒤의 재사용은
     * {@code PaymentWriter} 가 승인 전 소속 일치 검증으로 따로 막는다.
     *
     * @param requestedAfter 유효시간(TTL) 시작점 — 이보다 오래된 주문은 만료로 보아 재사용하지 않는다
     */
    Optional<Payment> findFirstByUserIdAndPlanIdAndStatusAndRequestedAtAfterOrderByRequestedAtDesc(
            Long userId, Long planId, PaymentStatus status, Instant requestedAfter);

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
