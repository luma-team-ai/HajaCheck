package com.hajacheck.admin.dto;

import com.hajacheck.membership.entity.PlanName;
import jakarta.validation.constraints.NotNull;
import java.util.List;

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
 * @param keepUserIds     하향으로 좌석이 넘칠 때 <b>관리자가 직접 유지할 구성원</b>(#890 Phase 2). 미지정
 *                        (null 또는 빈 리스트)이면 기존 동작(id 오름차순 자동 선정)을 그대로 따른다 — 하위
 *                        호환. 지정 시 서버가 강제하는 검증(회사 스코프·좌석 한도·owner 항상 유지·ACTIVE
 *                        ADMIN 불변식)은 {@code PlanDowngradeService} javadoc 참고. {@code change-preview}
 *                        와 동일한 값을 넘겨야 미리보기·실제 결과가 일치한다.
 */
public record AdminPlanChangeRequest(
        @NotNull PlanName planName, Boolean confirmOverflow, List<Long> keepUserIds) {

    public boolean overflowConfirmed() {
        return Boolean.TRUE.equals(confirmOverflow);
    }

    /** null 이면 빈 리스트로 정규화 — "미지정 = 기존 동작" 하위 호환을 호출부에서 매번 null 체크하지 않게 한다. */
    public List<Long> resolvedKeepUserIds() {
        return keepUserIds == null ? List.of() : keepUserIds;
    }
}
