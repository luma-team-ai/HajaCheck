package com.hajacheck.admin.dto;

import com.hajacheck.membership.entity.PlanName;
import jakarta.validation.constraints.NotNull;

/**
 * PATCH /api/admin/plan 요청 — 회사 구독을 이 요금제로 변경(#507).
 * planName 은 PlanName enum(FREE/STANDARD/ENTERPRISE) — 잘못된 값은 역직렬화 단계에서 400.
 *
 * @param confirmOverflow 하향으로 <b>한도를 넘게 되는 자원이 있을 때만</b> 의미가 있다(#890). 초과가
 *                        있는데 이 값이 참이 아니면 서버는 <b>아무것도 바꾸지 않고</b>
 *                        {@code PLAN_DOWNGRADE_CONFIRMATION_REQUIRED}(409)로 거절한다 — 관리자가 모르는
 *                        사이에 구성원이 정지되는 일을 막는 안전장치다. 무엇이 정지·읽기전용이 되는지는
 *                        {@code GET /api/admin/plan/change-preview} 로 먼저 확인한다. 초과가 없으면 이 값과
 *                        무관하게 그대로 변경된다(불필요한 마찰 금지). null 은 false 로 본다.
 */
public record AdminPlanChangeRequest(@NotNull PlanName planName, Boolean confirmOverflow) {

    public boolean overflowConfirmed() {
        return Boolean.TRUE.equals(confirmOverflow);
    }
}
