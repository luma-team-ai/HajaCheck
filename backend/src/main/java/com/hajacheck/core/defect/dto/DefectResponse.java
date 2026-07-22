package com.hajacheck.core.defect.dto;

import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import java.time.LocalDateTime;

public record DefectResponse(
        Long id,
        Long inspectionId,
        DefectType type,
        Double bboxX,
        Double bboxY,
        Double bboxW,
        Double bboxH,
        Double confidence,
        DefectGrade grade,
        DefectStatus status,
        boolean reviewed,
        boolean deleted,
        Double crackWidthMm,
        Double crackLengthMm,
        LocalDateTime createdAt,
        String facilityType
) {
    public static DefectResponse from(Defect defect, String facilityType) {
        return new DefectResponse(
                defect.getId(),
                defect.getInspectionId(),
                defect.getType(),
                defect.getBboxX(),
                defect.getBboxY(),
                defect.getBboxW(),
                defect.getBboxH(),
                defect.getConfidence(),
                defect.getGrade(),
                defect.getStatus(),
                defect.isReviewed(),
                defect.isDeleted(),
                defect.getCrackWidthMm(),
                defect.getCrackLengthMm(),
                defect.getCreatedAt(),
                facilityType
        );
    }
}
