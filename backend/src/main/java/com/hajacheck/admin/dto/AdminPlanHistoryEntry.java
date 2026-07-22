package com.hajacheck.admin.dto;

import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlanStatus;
import java.time.Instant;

/**
 * 회사 구독 변경 이력 1건 — user_plans 행 하나(언제 시작/종료, 어느 요금제, 상태)에 대응(#507).
 * AdminPlanRepository JPQL constructor expression 전용.
 */
public record AdminPlanHistoryEntry(
        Long subscriptionId,
        PlanName planName,
        UserPlanStatus status,
        Instant startedAt,
        Instant endedAt) {
}
