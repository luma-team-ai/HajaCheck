package com.hajacheck.core.defect.dto;

import com.hajacheck.core.defect.entity.DefectRevision;
import java.time.LocalDateTime;

/**
 * 하자 활동 기록(변경 이력) 응답 DTO — Entity 직접 노출 금지(§0). HAJA-314: 하자 상세 화면 활동 기록
 * 타임라인 조회용. defect_revisions는 이미 상태 전이 시점에 append-only로 기록되고 있어(DefectService
 * #updateStatus), 이 DTO는 그 기존 이력을 그대로 노출하는 조회 전용이다 — 신규 이력 기록 로직은 없다.
 */
public record DefectRevisionResponse(
        Long id,
        Long revisedBy,
        String fieldChanged,
        String oldValue,
        String newValue,
        String reason,
        LocalDateTime createdAt
) {
    public static DefectRevisionResponse from(DefectRevision revision) {
        return new DefectRevisionResponse(
                revision.getId(),
                revision.getRevisedBy(),
                revision.getFieldChanged(),
                revision.getOldValue(),
                revision.getNewValue(),
                revision.getReason(),
                revision.getCreatedAt()
        );
    }
}
