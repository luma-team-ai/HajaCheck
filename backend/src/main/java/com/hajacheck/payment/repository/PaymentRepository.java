package com.hajacheck.payment.repository;

import com.hajacheck.payment.entity.Payment;
import com.hajacheck.payment.entity.PaymentStatus;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
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

    /**
     * 특정 구독(user_plan)에 실제로 청구되어 승인 완료(PAID)된 금액 합계 — 잔여 기간 크레딧 상한
     * (#1146 / HAJA-550 리뷰 P1-A)의 근거 데이터.
     *
     * <p>크레딧 계산은 {@code current_period_start/end} × <b>라이브</b> {@code plans.price_monthly}
     * 파생치라, 그 자체로는 "실제로 낸 돈"을 전혀 보지 않는다 — 무결제 구독(V27 백필 추정치·
     * {@code payments} 테이블 도입(V20) 이전 모의 결제 시절 구독)이나 관리자 무결제 플랜 변경으로 이어진
     * 구독, 혹은 플랫폼 관리자가 나중에 가격을 올린 구독이면 <b>낸 적 없는 돈을 환급</b>하게 된다. 이
     * 합계로 크레딧 상한을 걸어 그 상한을 막는다({@code PaymentWriter#computeProratedCredit}).
     *
     * <p><b>SUM(합산) — 최신 1건이 아니다.</b> 같은 {@code user_plan} 에 PAID 가 여러 건 연결될 수 있다
     * (동시 승인 경합에서 먼저 커밋된 결제가 구독을 새로 만들고, 뒤이어 도착한 다른 READY 주문의 승인이
     * "이미 목표 플랜"으로 판정돼 같은 {@code user_plan} 에 연결만 하는 경로 —
     * {@code PaymentWriter#applyPlanTransition} 의 {@code target.alreadyOnTargetPlan()} 분기). 그 경우도
     * 사용자가 그 구독을 위해 실제로 낸 금액의 합이 정확한 상한이다 — 최신 1건만 보면 실제로 청구된
     * 금액보다 낮게 잡혀 상한이 부당하게 타이트해진다(사용자가 두 번 낸 돈인데 한 번만 인정하는 셈).
     *
     * @return 연결된 PAID 결제가 없으면 0
     */
    @Query("select coalesce(sum(p.amount), 0) from Payment p "
            + "where p.userPlanId = :userPlanId and p.status = :status")
    BigDecimal sumAmountByUserPlanIdAndStatus(
            @Param("userPlanId") Long userPlanId, @Param("status") PaymentStatus status);
}
