package com.hajacheck.core.defect.dto;

import com.hajacheck.core.defect.entity.DefectActionLog;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 조치 등록 제출 이력 응답 DTO — Entity 직접 노출 금지(§0). GET /api/defects/{id}/action-logs
 * (#1193/HAJA-569) 조회 전용이며, photoUrl은 기존 DefectResponse.actionPhotoUrl과 동일 패턴으로
 * 인가된 썸네일 엔드포인트를 재사용한다. actionAssigneeName은 엔티티가 Long id만 보유하므로
 * 서비스 계층에서 조회해 채운다.
 */
public record DefectActionLogResponse(
        Long id,
        String photoUrl,
        String actionContent,
        LocalDate actionDate,
        Long actionAssigneeId,
        String actionAssigneeName,
        LocalDateTime createdAt
) {
    public static DefectActionLogResponse from(DefectActionLog log, String actionAssigneeName) {
        return new DefectActionLogResponse(
                log.getId(),
                "/api/media/" + log.getMediaId() + "/thumbnail",
                log.getActionContent(),
                log.getActionDate(),
                log.getActionAssigneeId(),
                actionAssigneeName,
                log.getCreatedAt()
        );
    }
}
