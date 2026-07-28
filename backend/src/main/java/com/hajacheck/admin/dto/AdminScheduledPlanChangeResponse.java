package com.hajacheck.admin.dto;

import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.ScheduledPlanChange;
import com.hajacheck.membership.entity.ScheduledPlanChangeStatus;
import java.time.Instant;
import java.util.List;

/**
 * 플랜 하향 예약 응답(#1105 / HAJA-526) — {@code POST /api/admin/plan/scheduled-change} 의 본문이자
 * {@code GET /api/admin/plan} 응답의 {@code scheduledChange} 필드다.
 *
 * <p>대기 중(PENDING) 예약만 노출한다 — 적용·취소·실패한 예약은 화면이 "예약 있음"으로 오인하면 안 된다.
 * 예약 이력 자체는 {@code scheduled_plan_changes} 원장에 남고 별도 화면이 필요해지면 그때 계약을 넓힌다.
 *
 * @param targetPlanName 하향 대상 요금제명({@code PlanName}). 프론트가 요금제 라벨과 매칭할 수 있도록
 *                       id 가 아니라 이름으로 준다(다른 플랜 응답 계약과 동일).
 * @param effectiveAt    적용 예정 시각 = 신청 시점의 결제 주기 종료 시각(#1104).
 * @param keepUserIds    관리자가 유지하도록 고른 구성원 id. 빈 배열이면 자동 규칙(owner + id 오름차순).
 *                       ⚠️ 실행 시점에 재검증되므로 <b>이 목록이 그대로 유지된다는 보장은 아니다</b>
 *                       (퇴사·정지 id 는 드롭되고 부족분은 자동 규칙으로 보충된다).
 */
public record AdminScheduledPlanChangeResponse(
        Long id,
        String targetPlanName,
        Instant effectiveAt,
        List<Long> keepUserIds,
        String status) {

    public static AdminScheduledPlanChangeResponse of(ScheduledPlanChange change, Plan targetPlan) {
        return new AdminScheduledPlanChangeResponse(
                change.getId(),
                targetPlan.getName().name(),
                change.getEffectiveAt(),
                change.keepUserIdList(),
                change.getStatus().name());
    }

    /**
     * 취소 응답 전용 — 상태를 {@link ScheduledPlanChangeStatus#CANCELED} 로 <b>명시</b>한다.
     *
     * <p>취소는 조건부 벌크 UPDATE 로 수행하는데, 그 쿼리는 결제 승인 트랜잭션 안전을 위해
     * {@code clearAutomatically} 를 쓰지 않는다({@code ScheduledPlanChangeRepository
     * #transitionByUserPlanId} javadoc). 그래서 호출부가 들고 있는 엔티티는 1차 캐시의 옛 스냅샷이라
     * {@code status} 가 아직 PENDING 이다 — 그 값을 그대로 응답에 실으면 "취소했는데 대기 중"으로 보인다.
     */
    public static AdminScheduledPlanChangeResponse canceled(ScheduledPlanChange change, Plan targetPlan) {
        return new AdminScheduledPlanChangeResponse(
                change.getId(),
                targetPlan.getName().name(),
                change.getEffectiveAt(),
                change.keepUserIdList(),
                ScheduledPlanChangeStatus.CANCELED.name());
    }
}
