package com.hajacheck.core.defect.dto;

import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.facility.entity.Facility;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 하자 응답 DTO — Entity 직접 노출 금지(§0). 목록/상세 공용으로 사용한다
 * (bbox/이미지 등 별도 무거운 연관 엔티티가 없어 요약형과 상세형을 분리할 실익이 없음, HAJA-30 handoff 참고).
 *
 * <p>facilityName 은 대시보드 {@code PendingPriorityResponse}와 동일하게 "위치" 정보의 대체값이다 —
 * defects/media 스키마에 세부 위치(층·구역) 컬럼이 없어 시설물 명칭까지만 제공한다.
 *
 * <p>imageUrl(HAJA-314)은 defect.mediaId가 있을 때만 채워지며, 새 이미지 서빙 경로를 만들지 않고
 * {@link com.hajacheck.core.media.dto.MediaResponse}와 동일하게 기존 인가된 썸네일 엔드포인트
 * ({@code /api/media/{id}/thumbnail})를 재사용한다 — 원본은 직접 서빙하지 않는다는 PRD FR-2 정책을
 * 그대로 따른다.
 *
 * <p>actionPhotoUrl~actionAssigneeName(HAJA-393/#725)은 "조치 결과 등록"(PATCH /api/defects/{id}/action)
 * 이전에는 전부 null이다. actionAssigneeName은 엔티티가 Long id만 보유하므로 서비스 계층에서 조회해
 * {@link #from(Defect, String)}로 채운다. 이름을 조회하지 않는 목록 등에서는 기존 {@link #from(Defect)}가 null로 남긴다.
 */
public record DefectResponse(
        Long id,
        Long inspectionId,
        Long facilityId,
        String facilityName,
        String facilityType,
        DefectType type,
        String typeLabel,
        DefectGrade grade,
        DefectStatus status,
        Double confidence,
        boolean reviewed,
        Double bboxX,
        Double bboxY,
        Double bboxW,
        Double bboxH,
        Double crackWidthMm,
        Double crackLengthMm,
        String imageUrl,
        String actionPhotoUrl,
        String actionContent,
        LocalDate actionDate,
        Long actionAssigneeId,
        String actionAssigneeName,
        LocalDateTime createdAt
) {
    public static DefectResponse from(Defect defect) {
        return from(defect, null);
    }

    public static DefectResponse from(Defect defect, String actionAssigneeName) {
        Facility facility = defect.getInspection().getFacility();
        return new DefectResponse(
                defect.getId(),
                defect.getInspectionId(),
                facility.getId(),
                facility.getName(),
                facility.getType(),
                defect.getType(),
                defect.getType().label(),
                defect.getGrade(),
                defect.getStatus(),
                defect.getConfidence(),
                defect.isReviewed(),
                defect.getBboxX(),
                defect.getBboxY(),
                defect.getBboxW(),
                defect.getBboxH(),
                defect.getCrackWidthMm(),
                defect.getCrackLengthMm(),
                defect.getMediaId() == null ? null : "/api/media/" + defect.getMediaId() + "/thumbnail",
                defect.getActionMediaId() == null ? null : "/api/media/" + defect.getActionMediaId() + "/thumbnail",
                defect.getActionContent(),
                defect.getActionDate(),
                defect.getActionAssigneeId(),
                actionAssigneeName,
                defect.getCreatedAt()
        );
    }
}
