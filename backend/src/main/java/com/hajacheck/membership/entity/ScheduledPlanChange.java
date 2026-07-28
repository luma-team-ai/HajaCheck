package com.hajacheck.membership.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 플랜 하향 예약(#1105 / HAJA-526) — DDL {@code scheduled_plan_changes} 테이블 대응.
 *
 * <p><b>왜 존재하는가</b>: 관리자 콘솔의 요금제 변경({@code AdminPlanService#changePlan})은 즉시 전이라,
 * 하향을 신청하는 순간 초과 좌석이 그 자리에서 {@code SUSPENDED} 된다. 이미 낸 요금 기간이 남아 있어도
 * 권한이 바로 내려가므로 통상의 SaaS 관례("다음 결제 주기부터 적용")와 어긋난다. 이 행이 "지금 신청하고
 * {@code effective_at}(=신청 시점의 {@code user_plans.current_period_end}, #1104)에 적용"을 표현한다.
 *
 * <p><b>상태 전이는 조건부 UPDATE 로만 한다</b>({@code ScheduledPlanChangeRepository} 참고) — 이 엔티티에
 * 상태 변경 메서드를 두지 않는 이유다. 스케줄러는 사람 없이 계정을 정지시키므로 "이미 실행된 예약을 또
 * 실행"하는 일이 절대 없어야 하고, 그 보장은 {@code UPDATE ... WHERE status = 'PENDING'} 의 갱신 행 수로
 * 얻는다(엔티티 필드 대입은 읽은 시점과 쓰는 시점 사이의 경합을 막지 못한다). 중복 <b>예약</b> 자체는
 * 부분 UQ({@code uq_scheduled_plan_changes_pending})가 DB 레벨에서 막는다.
 *
 * <p>{@code created_at} 은 {@code startedAt} 이 생성시각 역할을 하는 {@link UserPlan} 과 달리 별도
 * 컬럼이라 팩토리에서 채운다(BaseTimeEntity 는 {@code updated_at} 까지 요구해 DDL 과 어긋난다).
 */
@Entity
@Getter
@Table(name = "scheduled_plan_changes")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScheduledPlanChange {

    private static final Long[] EMPTY_KEEP_USER_IDS = new Long[0];

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 예약을 건 시점의 구독 id. 이 구독이 다른 경로로 전이되면 실행 시점에 무효로 판정된다. */
    @Column(name = "user_plan_id", nullable = false)
    private Long userPlanId;

    @Column(name = "target_plan_id", nullable = false)
    private Long targetPlanId;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    /**
     * 관리자가 유지하도록 고른 구성원 id({@code PlanDowngradeService#preview} 의 keepUserIds 와 같은 의미).
     * 빈 배열이면 자동 규칙(owner + id 오름차순).
     *
     * <p>⚠️ <b>실행 시점에 반드시 재검증한다</b> — 이 목록은 한 달 가까이 보관되므로, 그 사이 퇴사·정지된
     * id 가 섞여 있으면 {@code PlanDowngradeService} 의 스코프 검증이 실패해 예약이 통째로 죽거나(FAILED)
     * 좌석이 잘못 배분된다({@code ScheduledPlanChangeWriter#resolveKeepUserIds}).
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "keep_user_ids", nullable = false)
    private Long[] keepUserIds;

    /**
     * 신청 시점에 관리자가 {@code confirmOverflow=true} 로 확인한 <b>정지 예정 인원 수</b>(리뷰 P2-4).
     *
     * <p>{@code confirmOverflow} 는 "그 시점 미리보기"에 대한 동의다. 예약은 한 달 가까이 보관되므로 그
     * 사이 구성원이 늘면 실행 시점 정지 인원이 동의받은 수를 크게 넘어설 수 있다(확인 2명 → 실행 15명).
     * 대상이 FREE(1석)라 폭발 반경이 특히 크고, 정지 즉시 {@code SessionUserRevalidationFilter} 가 그
     * 사용자의 세션을 죽인다. 그래서 실행 시점에 이 값과 대조해, 넘으면 적용하지 않고 FAILED 로 종료한다
     * ({@code ScheduledPlanChangeWriter}) — 상위 요금제가 유지되므로 아무도 잘못 정지되지 않는다.
     */
    @Column(name = "confirmed_seat_overflow", nullable = false)
    private int confirmedSeatOverflow;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "scheduled_plan_change_status_type", nullable = false)
    private ScheduledPlanChangeStatus status;

    /** 예약을 만든 사용자(회사 owner) — 오예약 추적용 감사 정보. */
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "applied_at")
    private Instant appliedAt;

    @Column(name = "failure_reason")
    private String failureReason;

    private ScheduledPlanChange(Long userPlanId, Long targetPlanId, Instant effectiveAt,
            List<Long> keepUserIds, int confirmedSeatOverflow, Long createdBy) {
        this.userPlanId = userPlanId;
        this.targetPlanId = targetPlanId;
        this.effectiveAt = effectiveAt;
        this.keepUserIds = toArray(keepUserIds);
        this.confirmedSeatOverflow = confirmedSeatOverflow;
        this.status = ScheduledPlanChangeStatus.PENDING;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    /**
     * 하향 예약 생성 — 항상 {@link ScheduledPlanChangeStatus#PENDING} 으로 시작한다.
     *
     * @param effectiveAt           적용 시각. 호출부가 <b>신청 시점의
     *                              {@code user_plans.current_period_end}</b> 를 그대로 넘긴다(#1104) —
     *                              잔여 기간이 끝나는 순간이 곧 적용 시점이다.
     * @param keepUserIds           유지할 구성원 선택. {@code null}·빈 리스트는 자동 규칙(owner + id
     *                              오름차순)을 뜻한다.
     * @param confirmedSeatOverflow 신청 시점 미리보기의 정지 예정 인원 수 — 실행 시점 상한이 된다
     *                              ({@link #getConfirmedSeatOverflow()} javadoc).
     */
    public static ScheduledPlanChange schedule(Long userPlanId, Long targetPlanId, Instant effectiveAt,
            List<Long> keepUserIds, int confirmedSeatOverflow, Long createdBy) {
        return new ScheduledPlanChange(userPlanId, targetPlanId, effectiveAt, keepUserIds,
                confirmedSeatOverflow, createdBy);
    }

    /** 유지 대상 선택을 불변 리스트로 노출한다(배열 필드를 밖으로 흘리지 않는다). */
    public List<Long> keepUserIdList() {
        return keepUserIds == null ? List.of() : List.of(keepUserIds);
    }

    private static Long[] toArray(List<Long> keepUserIds) {
        if (keepUserIds == null || keepUserIds.isEmpty()) {
            return EMPTY_KEEP_USER_IDS;
        }
        // 중복·null 은 호출부(서비스)가 검증하지만, 배열로 옮기는 이 자리에서 방어적으로 한 번 더 거른다.
        return keepUserIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toArray(Long[]::new);
    }

    /** 로깅·응답용 문자열(개인정보 없음 — id 와 시각뿐). */
    @Override
    public String toString() {
        return "ScheduledPlanChange{id=%d, userPlanId=%d, targetPlanId=%d, effectiveAt=%s, status=%s, keepUserIds=%s}"
                .formatted(id, userPlanId, targetPlanId, effectiveAt, status,
                        keepUserIds == null ? "[]" : Arrays.toString(keepUserIds));
    }
}
