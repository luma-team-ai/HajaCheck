package com.hajacheck.membership.repository;

import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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

    /**
     * 결제 주기 만료 강등 대상 건수(#1145 / HAJA-549) — {@code PlanExpiryScheduler} 가 강등을 시작하기
     * <b>전에</b> 1회 실행 상한({@code hajacheck.plan.expiry.max-per-run})과 대조하는 데 쓴다.
     * 조회 조건은 {@link #findExpiryTargetIds} 와 <b>정확히 같아야 한다</b> — 두 판정이 갈라지면 상한
     * 검사를 통과한 뒤 그보다 많은 행을 강등하게 된다.
     */
    @Query("""
            select count(up) from UserPlan up
            where up.status = :status
              and up.currentPeriodEnd is not null
              and up.currentPeriodEnd < :threshold
            """)
    long countExpiryTargets(@Param("status") UserPlanStatus status, @Param("threshold") Instant threshold);

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
     * <p>FREE 는 {@code current_period_end IS NULL}(#1104 규칙)이라 이 조건에서 자연 배제된다 —
     * plans 가격으로 다시 필터하지 않는다(#1145 §2 확정: 두 판정이 갈라지면 #1104의 승계 규칙과 어긋난다).
     */
    @Query("""
            select up.id from UserPlan up
            where up.status = :status
              and up.currentPeriodEnd is not null
              and up.currentPeriodEnd < :threshold
              and up.id > :lastId
            order by up.id asc
            """)
    List<Long> findExpiryTargetIds(@Param("status") UserPlanStatus status,
            @Param("threshold") Instant threshold, @Param("lastId") Long lastId, Pageable pageable);
}
