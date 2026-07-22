package com.hajacheck.core.defect.dto;

import com.hajacheck.core.defect.entity.DefectStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 하자 상태 전이 요청(HAJA-26 2차). 정방향 한 단계 전이 순서 강제 및 역행/건너뛰기 시 사유 필수
 * 규칙은 {@link com.hajacheck.core.defect.entity.Defect#changeStatus(DefectStatus, String)}
 * 엔티티 메서드가 담당한다 — 정방향 전이는 {@code reason} 없이 허용, 역행/건너뛰기는
 * {@code reason} 누락 시 DomainValidationException → 400 INVALID_INPUT으로 거부된다.
 */
public record DefectStatusUpdateRequest(
        @NotNull DefectStatus status,
        @Size(max = 500) String reason
) {
}
