package com.hajacheck.membership.repository;

import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserPlanRepository extends JpaRepository<UserPlan, Long> {

    Optional<UserPlan> findFirstByCompanyIdAndStatusOrderByStartedAtDesc(Long companyId, UserPlanStatus status);

    Optional<UserPlan> findFirstByUserIdAndStatusOrderByStartedAtDesc(Long userId, UserPlanStatus status);

    // 가입 시 FREE 배정 멱등 판별(#517) — ACTIVE/UPGRADE_REQUESTED 중 하나라도 있으면 이미 구독 중이므로 재배정하지 않는다.
    boolean existsByCompanyIdAndStatusIn(Long companyId, Collection<UserPlanStatus> statuses);

    boolean existsByUserIdAndStatusIn(Long userId, Collection<UserPlanStatus> statuses);

    // 플랫폼 관리자 플랜·쿼터 관리(#624) — 회사 귀속 활성 구독 전체(companyId → plan 매핑용, 회사당 최대 1건).
    List<UserPlan> findByCompanyIdIsNotNullAndStatus(UserPlanStatus status);

    // 플랫폼 관리자 서비스 통계(#633) — 회사 귀속 구독의 전체 이력(ACTIVE+EXPIRED 등). expire()가 기존 행을
    // EXPIRED로 내리고 새 행을 발급하는 방식(UserPlan 클래스 상단 javadoc)이라, 이 전체 이력만으로 과거
    // 특정 시점의 구독 여부·플랜 전환 이력을 순수 조회로 재구성할 수 있다(스냅샷 테이블 불필요).
    List<UserPlan> findByCompanyIdIsNotNull();

    // 플랫폼 관리자 날짜별 상담 목록(#1168) — 페이지 내 고객(userId) 들의 활성 개인 구독을 배치 조회
    // (resolveCounselorNames 와 동일한 N+1 방지 패턴).
    // ⚠️ 개인 구독 전용이다(#1263) — user_plans 는 owner XOR(ck_user_plans_owner_xor)라 회사 귀속 구독 행은
    // user_id IS NULL 이므로 이 IN 절에 애초에 걸리지 않는다. 회사 소속 고객(user.companyId != null)의
    // 상담 권한은 CounselTicketService#requireCounselorAccess 가 보여주듯 회사 구독에서 나오므로,
    // 호출부는 companyId 집합에 대해 findByCompanyIdInAndStatus 를 함께 조회해야 한다.
    List<UserPlan> findByUserIdInAndStatus(Collection<Long> userIds, UserPlanStatus status);

    // 플랫폼 관리자 날짜별 상담 목록(#1263) — 위 메서드의 회사 짝. 페이지 내 고객들이 속한 회사(companyId)
    // 들의 활성 구독을 한 번에 모아 조회한다(고객 수와 무관하게 쿼리 1개 — N+1 없음).
    List<UserPlan> findByCompanyIdInAndStatus(Collection<Long> companyIds, UserPlanStatus status);

    /**
     * 결제 주기 만료 강등 대상 건수(#1145 / HAJA-549) — {@code PlanExpiryScheduler} 가 강등을 시작하기
     * <b>전에</b> 1회 실행 상한({@code hajacheck.plan.expiry.max-per-run})과 대조하는 데 쓴다.
     * 조회 조건은 {@link #findExpiryTargetIds} 와 <b>정확히 같아야 한다</b> — 두 판정이 갈라지면 상한
     * 검사를 통과한 뒤 그보다 많은 행을 강등하게 된다.
     *
     * <p>⚠️ {@code cast(:notBefore as Instant)} 의 cast 는 장식이 아니다. 그냥 {@code :notBefore is null}
     * 로 쓰면 PostgreSQL 이 바인딩 파라미터의 타입을 추론하지 못해 "could not determine data type of
     * parameter" 로 쿼리 자체가 실패한다(실측 확인). 선택적 컷오프를 표현하려면 이 cast 가 필요하다.
     */
    @Query("""
            select count(up) from UserPlan up
            where up.status in :statuses
              and up.currentPeriodEnd is not null
              and up.currentPeriodEnd < :threshold
              and (cast(:notBefore as Instant) is null or up.currentPeriodEnd >= :notBefore)
            """)
    long countExpiryTargets(@Param("statuses") Collection<UserPlanStatus> statuses,
            @Param("threshold") Instant threshold, @Param("notBefore") Instant notBefore);

    /**
     * 결제 주기 만료 강등 대상 id(#1145 / HAJA-549) — id 오름차순 keyset 페이징이다.
     *
     * <p><b>왜 offset 페이징이 아닌가</b>: 이 배치는 순회하면서 대상 행을 EXPIRED 로 내려 <b>조회
     * 결과집합에서 빼버린다</b>. offset 페이징이면 처리한 만큼 결과가 앞으로 당겨져 두 번째 페이지부터
     * 대상이 통째로 건너뛰어진다. {@code lastId} 기준 keyset 이면 이미 지나간 구간을 다시 읽지 않으면서도
     * 누락이 생기지 않는다.
     *
     * <p>엔티티가 아니라 id 만 반환하는 이유: 실제 전이는 건별 독립 트랜잭션
     * ({@code PlanExpiryWriter}, REQUIRES_NEW)에서 <b>다시 로딩</b>해 수행해야 하므로(스케줄러 자신은
     * 트랜잭션을 열지 않는다) 여기서 읽은 엔티티는 어차피 준영속이다.
     *
     * <p><b>⚠️ {@code statuses} 는 반드시 엔타이틀먼트 판정과 같은 집합이어야 한다</b>(리뷰 P1) —
     * {@code ACTIVE} + {@code UPGRADE_REQUESTED}. {@code QuotaService#findLivePlan} 이 두 상태를 모두
     * "살아있는 구독"으로 인정해 유료 한도를 계속 적용하는데, 여기서 ACTIVE 만 보면
     * {@code POST /api/me/plan/upgrade-inquiry}({@code UserPlan#requestUpgrade} 가 새 행을 만들지 않고
     * 기존 행의 status 만 바꾼다) 를 <b>한 번</b> 호출하는 것만으로 만료 강제를 영구히 회피하면서 유료
     * 한도를 계속 쓸 수 있다 — 이 배치가 없애려던 "한 번 결제하면 무기한"이 요청 1건으로 복원된다.
     *
     * <p>FREE 는 {@code current_period_end IS NULL}(#1104 규칙)이라 이 조건에서 자연 배제된다 —
     * plans 가격으로 다시 필터하지 않는다(#1145 §2 확정: 두 판정이 갈라지면 #1104의 승계 규칙과 어긋난다).
     *
     * @param notBefore 강등 하한 컷오프({@code hajacheck.plan.expiry.not-before}). {@code null} 이면 제한
     *                  없음. V27 백필이 채운 "추정 만료일" 과거 구간을 <b>쿼리 단계에서</b> 배제하는 용도라
     *                  건수 기반 상한과 달리 대상이 소수여도 작동한다.
     */
    @Query("""
            select up.id from UserPlan up
            where up.status in :statuses
              and up.currentPeriodEnd is not null
              and up.currentPeriodEnd < :threshold
              and (cast(:notBefore as Instant) is null or up.currentPeriodEnd >= :notBefore)
              and up.id > :lastId
            order by up.id asc
            """)
    List<Long> findExpiryTargetIds(@Param("statuses") Collection<UserPlanStatus> statuses,
            @Param("threshold") Instant threshold, @Param("notBefore") Instant notBefore,
            @Param("lastId") Long lastId, Pageable pageable);

    /**
     * <b>미결제 유예가 만료된</b> 구독 건수(#1177) — {@code ScheduledPlanChangeScheduler} 2단계가 강등을
     * 시작하기 전에 1회 실행 상한과 대조하는 데 쓴다. 조회 조건은 {@link #findPaymentGraceExpiredIds} 와
     * <b>정확히 같아야 한다</b>(두 판정이 갈라지면 상한 검사를 통과한 뒤 그보다 많은 행을 강등한다).
     */
    @Query("""
            select count(up) from UserPlan up
            where up.status in :statuses
              and up.paymentPendingUntil is not null
              and up.paymentPendingUntil < :now
            """)
    long countPaymentGraceExpired(@Param("statuses") Collection<UserPlanStatus> statuses,
            @Param("now") Instant now);

    /**
     * <b>미결제 유예가 만료된</b> 구독 id(#1177) — id 오름차순 keyset 페이징
     * ({@link #findExpiryTargetIds} 와 같은 이유로 offset 페이징을 쓰지 않는다: 강등된 구독은 EXPIRED 가
     * 되어 결과집합에서 빠진다).
     *
     * <p>판정이 {@code payment_pending_until} 컬럼 하나인 이유는 {@code PaymentGraceService} javadoc
     * 참고(파생 판정이 만들던 네 가지 결함이 명시 컬럼으로 사라진다). 부분 인덱스
     * {@code idx_user_plans_payment_pending}(V33)이 이 조회 전용이다 — 정상 구독은 컬럼이 NULL 이라
     * 인덱스에서 통째로 빠진다.
     *
     * @param statuses 엔타이틀먼트 판정과 같은 집합({@code PlanExpiryWriter#LIVE_STATUSES})이어야 한다 —
     *                 근거는 {@link #findExpiryTargetIds} javadoc 과 동일하다.
     */
    @Query("""
            select up.id from UserPlan up
            where up.status in :statuses
              and up.paymentPendingUntil is not null
              and up.paymentPendingUntil < :now
              and up.id > :lastId
            order by up.id asc
            """)
    List<Long> findPaymentGraceExpiredIds(@Param("statuses") Collection<UserPlanStatus> statuses,
            @Param("now") Instant now, @Param("lastId") Long lastId, Pageable pageable);

    /**
     * 강등 대상 구독을 <b>행 잠금</b>과 함께 읽는다(#1145, 리뷰 P3-5) — {@code PlanExpiryWriter} 의
     * 트랜잭션 내 재검증 전용.
     *
     * <p>재검증을 잠금 없는 {@code findById} 로 하면, 스케줄러가 id 를 읽은 뒤 writer 가 flush 하기까지의
     * 창에서 다른 트랜잭션(업그레이드 문의·결제 승인·관리자 변경)이 같은 행을 커밋할 수 있고 그 갱신이
     * 조용히 덮인다. {@code PESSIMISTIC_WRITE} 로 읽으면 그 창에서 경쟁 트랜잭션의 UPDATE 가 우리 커밋까지
     * 대기하므로, 우리가 보는 상태가 곧 판정 시점의 확정 상태가 된다(다중 인스턴스에서 같은 구독을 동시에
     * 강등하는 것도 이 잠금이 직렬화한다).
     *
     * <p>⚠️ <b>남는 한계</b>: {@code UserPlan} 에는 {@code @Version} 이 없어, 잠금 해제 후 실행되는 상대
     * 트랜잭션이 <b>자신이 먼저 읽어 둔 준영속 스냅샷</b>으로 UPDATE 하면 우리 결과를 되덮을 수 있다(lost
     * update). 이는 이 배치만의 문제가 아니라 {@code AdminPlanService}·{@code PaymentWriter} 등 기존 전이
     * 경로 전체가 공유하는 노출이라, 낙관적 락 도입은 별도 과제로 둔다({@code PaymentRepository
     * #findByIdForUpdate} 와 같은 수준의 방어까지가 이번 범위).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select up from UserPlan up where up.id = :id")
    Optional<UserPlan> findByIdForUpdate(@Param("id") Long id);

    /**
     * 현재 트랜잭션의 잠금 대기 상한을 설정한다(#1145, 리뷰 NEW-2) — {@link #findByIdForUpdate} 를 부르기
     * <b>직전</b>에 같은 트랜잭션에서 호출한다.
     *
     * <p><b>왜 {@code @QueryHints(jakarta.persistence.lock.timeout)} 가 아닌가</b>: 그 힌트는 이 스택에서
     * <b>아무 효과가 없다</b>. {@code PostgreSQLDialect.supportsWait()} 가 false 라(hibernate-core 6.5.3
     * 바이트코드로 확인) 밀리초 단위 타임아웃은 조용히 버려지고 평범한 {@code for update} 로 나간다 —
     * 값을 넣어도 무한 대기 그대로다. PostgreSQL 에서 실제로 동작하는 수단은 {@code NOWAIT}(대기 0) /
     * {@code SKIP_LOCKED} / 세션·트랜잭션 {@code lock_timeout} 뿐이다.
     *
     * <p>{@code NOWAIT} 대신 {@code lock_timeout} 을 고른 이유: NOWAIT 는 순간적인 경합(같은 구독에 대한
     * 결제 승인 등)에도 즉시 실패해 정당한 강등이 <b>하루</b> 미뤄진다. 짧은 상한을 두면 그 정도 경합은
     * 기다려 넘기고 진짜로 오래 잡힌 행만 실패로 격리된다. 게다가 {@code lock_timeout} 은 이어지는
     * UPDATE/INSERT 의 잠금 대기까지 함께 덮어 "배치 스레드가 묶이는" 상황을 더 넓게 막는다.
     *
     * <p>{@code set_config(..., is_local = true)} 라 <b>트랜잭션 스코프</b>다 — 커밋/롤백과 함께 원복되므로
     * 커넥션 풀을 통해 다른 작업으로 새지 않는다({@code SET LOCAL} 은 파라미터 바인딩이 안 돼 같은 기능의
     * 함수 형태를 쓴다).
     *
     * @param timeoutMs 대기 상한(ms) 문자열. PostgreSQL 규약상 {@code "0"} 은 무제한이다.
     */
    @Query(value = "select set_config('lock_timeout', :timeoutMs, true)", nativeQuery = true)
    String applyLockTimeout(@Param("timeoutMs") String timeoutMs);
}
