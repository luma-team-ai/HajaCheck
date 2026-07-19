package com.hajacheck.core.defect.dto;

import com.hajacheck.core.defect.entity.DefectStatus;
import jakarta.validation.constraints.NotNull;

/**
 * 하자 상태 전이 요청(HAJA-30, 2단계). 신규→검수확정→조치대기→조치중→조치완료 순서 강제는
 * {@link com.hajacheck.core.defect.entity.Defect#changeStatus(DefectStatus)} 엔티티 메서드가 담당한다
 * (역행/스킵 요청은 DomainStateTransitionException → 409 INVALID_STATE_TRANSITION).
 */
public record DefectStatusUpdateRequest(
        @NotNull DefectStatus status
) {
}
