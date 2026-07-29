package com.hajacheck.membership.repository;

import com.hajacheck.membership.entity.ScheduledPlanChange;
import com.hajacheck.membership.entity.ScheduledPlanChangeStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 플랜 하향 예약 원장 조회·전이(#1105 / HAJA-526).
 *
 * <p><b>상태 전이는 전부 조건부 UPDATE 다</b>({@code ... where status = :expectedStatus}). 이 배치는 사람
 * 없이 계정을 정지시키므로 "이미 실행된 예약을 또 실행"하는 일이 절대 없어야 하는데, 엔티티 필드 대입은
 * 읽은 시점과 쓰는 시점 사이의 경합(다중 인스턴스·재실행)을 막지 못한다. 갱신 행 수가 1일 때만 "이번
 * 실행이 이 예약을 점유했다"고 인정한다 — {@code PlanExpiryWriter} 가 부분 UQ 로 얻는 보장을 여기서는
 * 조건부 UPDATE 로 얻는 셈이다.
 *
 * <p>⚠️ <b>enum 은 반드시 바인딩 파라미터로 넘긴다</b>({@code :status}). JPQL 에 enum 상수를 리터럴로
 * 쓰면({@code s.status = com.hajacheck...PENDING}) Hibernate 가 PG named enum 캐스트를
 * <b>Java 클래스 이름</b>으로 렌더링해({@code 'PENDING'::ScheduledPlanChangeStatus}) 실행 시점에
 * {@code type "scheduledplanchangestatus" does not exist} 로 죽는다(실측 확인 — 컬럼의
 * {@code columnDefinition} 인 {@code scheduled_plan_change_status_type} 을 쓰지 않는다).
 * 그래서 공개 메서드는 {@code default} 래퍼로 두고, 실제 쿼리는 상태를 파라미터로 받는다 —
 * 호출부가 상태 상수를 잘못 넘길 여지도 함께 없앤다.
 */
public interface ScheduledPlanChangeRepository extends JpaRepository<ScheduledPlanChange, Long> {

    /** 이 구독에 걸린 대기 예약(부분 UQ 로 최대 1건) — 조회 화면·취소 API 가 쓴다. */
    Optional<ScheduledPlanChange> findFirstByUserPlanIdAndStatus(
            Long userPlanId, ScheduledPlanChangeStatus status);

    /**
     * 실행 대상 건수 — 스케줄러가 1회 실행 상한과 대조하는 데 쓴다. 조회 조건은 {@link #findDueIds} 와
     * <b>정확히 같아야 한다</b>(두 판정이 갈라지면 상한 검사를 통과한 뒤 그보다 많은 예약을 실행하게 된다).
     */
    default long countDue(Instant now) {
        return countByStatusAndEffectiveAtLessThanEqual(ScheduledPlanChangeStatus.PENDING, now);
    }

    /**
     * 실행 대상 예약 id — id 오름차순 keyset 페이징이다.
     *
     * <p><b>왜 offset 페이징이 아닌가</b>: 처리한 예약은 APPLIED/CANCELED/FAILED 가 되어 <b>결과집합에서
     * 빠진다</b>. offset 페이징이면 처리한 만큼 결과가 앞으로 당겨져 두 번째 페이지부터 대상이 통째로
     * 건너뛰어진다({@code UserPlanRepository#findExpiryTargetIds} 와 동일 이유).
     *
     * <p>엔티티가 아니라 id 만 반환하는 이유: 실제 실행은 건별 독립 트랜잭션
     * ({@code ScheduledPlanChangeWriter}, REQUIRES_NEW)에서 <b>다시 로딩</b>해 수행하므로(스케줄러 자신은
     * 트랜잭션을 열지 않는다) 여기서 읽은 엔티티는 어차피 준영속이다.
     */
    default List<Long> findDueIds(Instant now, Long lastId, Pageable pageable) {
        return findDueIds(ScheduledPlanChangeStatus.PENDING, now, lastId, pageable);
    }

    /**
     * 예약을 "이번 실행이 점유했다"고 표시한다(PENDING → APPLIED). <b>갱신 행 수가 1일 때만</b> 실행을
     * 계속해야 한다 — 0이면 다른 인스턴스/이전 회차가 이미 처리한 것이므로 아무것도 하지 않는다.
     *
     * <p>{@code clearAutomatically = true} 로 영속성 컨텍스트를 비운다: 벌크 UPDATE 는 1차 캐시를
     * 우회하므로, 같은 트랜잭션에 남은 옛 스냅샷(status=PENDING)이 이후 조회에 섞이면 안 된다. 그래서
     * 호출부는 이 호출 <b>이전에</b> 필요한 값(대상 요금제·유지 목록)을 전부 읽어 두고, 이 호출 이후에
     * 구독 엔티티를 다시 읽어(행 잠금은 트랜잭션 스코프라 유지된다) 전이 쓰기를 시작한다.
     */
    default int markApplied(Long id, Instant appliedAt) {
        return markAppliedById(id, ScheduledPlanChangeStatus.PENDING,
                ScheduledPlanChangeStatus.APPLIED, appliedAt);
    }

    /**
     * 종료 실패로 표시한다(PENDING → FAILED). <b>실행 트랜잭션과는 다른 트랜잭션</b>에서 호출해야 한다 —
     * 실패한 실행은 롤백되므로 같은 트랜잭션에 실으면 실패 기록까지 함께 사라진다.
     *
     * <p>FAILED 는 종료 상태라 자동 재시도하지 않는다. 재시도로 풀릴 수 있는 실패(잠금 경합·활성 구독
     * 경합·일시적 인프라 장애)는 이 메서드를 부르지 않고 PENDING 으로 남긴다
     * ({@code ScheduledPlanChangeScheduler} 의 실패 분류 참고).
     */
    default int markFailed(Long id, String failureReason) {
        return transitionById(id, ScheduledPlanChangeStatus.PENDING,
                ScheduledPlanChangeStatus.FAILED, failureReason);
    }

    /**
     * 예약 1건을 무효화한다(PENDING → CANCELED) — 실행 시점에 "예약이 의미를 잃었다"고 판정된 건 전용
     * ({@code user_plan} 이 이미 전이됨·대상 요금제가 더 이상 하향이 아님 등). 실패가 아니라 무효라
     * FAILED 로 남기지 않는다: 매 회차 WARN 을 띄울 사고가 아니고, 사람이 할 일도 없다.
     */
    default int cancelPendingById(Long id, String reason) {
        return transitionById(id, ScheduledPlanChangeStatus.PENDING,
                ScheduledPlanChangeStatus.CANCELED, reason);
    }

    /**
     * 이 구독에 걸린 대기 예약을 무효화한다(PENDING → CANCELED). 신청자의 명시적 취소와, 구독이 다른
     * 경로로 전이돼 예약이 의미를 잃은 경우 양쪽이 쓴다.
     *
     * @return 갱신된 행 수(0 = 취소할 대기 예약이 없었음). 취소 API 는 이 값으로
     *         {@code PLAN_SCHEDULED_CHANGE_NOT_FOUND} 판정을 하므로 <b>조회 후 판정이 아니라 이 갱신
     *         결과로</b> 판정해야 경합에서도 정확하다.
     */
    default int cancelPendingByUserPlanId(Long userPlanId, String reason) {
        return transitionByUserPlanId(userPlanId, ScheduledPlanChangeStatus.PENDING,
                ScheduledPlanChangeStatus.CANCELED, reason);
    }

    /**
     * 실행 대상 예약을 <b>행 잠금</b>과 함께 읽는다 — {@code ScheduledPlanChangeWriter} 의 트랜잭션 내
     * 재검증 전용. 다중 인스턴스가 같은 예약을 동시에 실행하는 것을 직렬화한다
     * ({@code UserPlanRepository#findByIdForUpdate} 와 같은 취지).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ScheduledPlanChange s where s.id = :id")
    Optional<ScheduledPlanChange> findByIdForUpdate(@Param("id") Long id);

    /**
     * 이 구독의 대기 예약에 <b>행 잠금만</b> 걸어 둔다(취소하지 않는다) —
     * {@code ScheduledPlanChangeCanceller#lockPendingForTransition} 전용이며, 존재 이유는 그쪽 javadoc 의
     * <b>잠금 순서</b> 절이다. 대기 예약이 없으면 잠글 행이 없어 아무 잠금도 잡지 않는다(그게 대부분의 경우다).
     */
    default List<ScheduledPlanChange> lockPendingByUserPlanId(Long userPlanId) {
        return lockPendingByUserPlanId(userPlanId, ScheduledPlanChangeStatus.PENDING);
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ScheduledPlanChange s where s.userPlanId = :userPlanId and s.status = :status")
    List<ScheduledPlanChange> lockPendingByUserPlanId(@Param("userPlanId") Long userPlanId,
            @Param("status") ScheduledPlanChangeStatus status);


    // ── 아래는 default 래퍼 전용 쿼리다(상태를 파라미터로 받는 이유는 클래스 javadoc 참고) ────────────

    long countByStatusAndEffectiveAtLessThanEqual(ScheduledPlanChangeStatus status, Instant effectiveAt);

    @Query("""
            select s.id from ScheduledPlanChange s
            where s.status = :status
              and s.effectiveAt <= :now
              and s.id > :lastId
            order by s.id asc
            """)
    List<Long> findDueIds(@Param("status") ScheduledPlanChangeStatus status, @Param("now") Instant now,
            @Param("lastId") Long lastId, Pageable pageable);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ScheduledPlanChange s
               set s.status = :newStatus,
                   s.appliedAt = :appliedAt
             where s.id = :id
               and s.status = :expectedStatus
            """)
    int markAppliedById(@Param("id") Long id,
            @Param("expectedStatus") ScheduledPlanChangeStatus expectedStatus,
            @Param("newStatus") ScheduledPlanChangeStatus newStatus,
            @Param("appliedAt") Instant appliedAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ScheduledPlanChange s
               set s.status = :newStatus,
                   s.failureReason = :reason
             where s.id = :id
               and s.status = :expectedStatus
            """)
    int transitionById(@Param("id") Long id,
            @Param("expectedStatus") ScheduledPlanChangeStatus expectedStatus,
            @Param("newStatus") ScheduledPlanChangeStatus newStatus,
            @Param("reason") String reason);

    /**
     * ⚠️ <b>이 쿼리만 {@code clearAutomatically} 를 쓰지 않는다</b>(다른 전이 쿼리와 다르다). 호출부에
     * {@code PlanTransitionService#transitionTo} 가 있는데, 그 메서드는 {@code MANDATORY} 라 <b>결제 승인
     * 트랜잭션({@code PaymentWriter#applyPlanTransition}) 안에서</b> 실행된다. 거기서 영속성 컨텍스트를
     * 비우면 호출부가 들고 있던 {@code Payment} 엔티티가 준영속이 되어, 전이 직후의
     * {@code payment.linkUserPlan(...)} 이 <b>조용히 유실</b>된다 — "결제됨 + user_plan_id NULL"이라는,
     * 그 클래스가 가장 경계하는 상태를 우리가 만들게 된다. 대신 이 쿼리의 호출부들은 갱신 이후
     * {@code ScheduledPlanChange} 엔티티를 다시 읽지 않으므로 1차 캐시 staleness 노출이 없다.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update ScheduledPlanChange s
               set s.status = :newStatus,
                   s.failureReason = :reason
             where s.userPlanId = :userPlanId
               and s.status = :expectedStatus
            """)
    int transitionByUserPlanId(@Param("userPlanId") Long userPlanId,
            @Param("expectedStatus") ScheduledPlanChangeStatus expectedStatus,
            @Param("newStatus") ScheduledPlanChangeStatus newStatus,
            @Param("reason") String reason);
}
