package com.hajacheck.core.inspection.dto;

import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record InspectionResponse(
        Long id,
        Long facilityId,
        Long createdBy,
        Long assignedInspectorId,
        Integer roundNo,
        LocalDate inspectionDate,
        InspectionStatus status,
        LocalDateTime createdAt
) {
    public static InspectionResponse from(Inspection inspection) {
        return new InspectionResponse(
                inspection.getId(),
                inspection.getFacilityId(),
                inspection.getCreatedBy(),
                inspection.getAssignedInspectorId(),
                inspection.getRoundNo(),
                inspection.getInspectionDate(),
                inspection.getStatus(),
                inspection.getCreatedAt()
        );
    }
}
