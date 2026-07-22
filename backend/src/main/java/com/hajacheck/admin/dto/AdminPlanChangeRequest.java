package com.hajacheck.admin.dto;

import com.hajacheck.membership.entity.PlanName;
import jakarta.validation.constraints.NotNull;

/**
 * PATCH /api/admin/plan 요청 — 회사 구독을 이 요금제로 변경(#507).
 * planName 은 PlanName enum(FREE/STANDARD/ENTERPRISE) — 잘못된 값은 역직렬화 단계에서 400.
 */
public record AdminPlanChangeRequest(@NotNull PlanName planName) {
}
