package com.hajacheck.core.defect.dto;

import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.facility.entity.Facility;
import java.time.LocalDateTime;

/**
 * 하자 응답 DTO — Entity 직접 노출 금지(§0). 목록/상세 공용으로 사용한다
 * (bbox/이미지 등 별도 무거운 연관 엔티티가 없어 요약형과 상세형을 분리할 실익이 없음, HAJA-30 handoff 참고).
 *
 * <p>facilityName 은 대시보드 {@code PendingPriorityResponse}와 동일하게 "위치" 정보의 대체값이다 —
 * defects/media 스키마에 세부 위치(층·구역) 컬럼이 없어 시설물 명칭까지만 제공한다.
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
        LocalDateTime createdAt
) {
    public static DefectResponse from(Defect defect) {
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
                defect.getCreatedAt()
        );
    }
}
