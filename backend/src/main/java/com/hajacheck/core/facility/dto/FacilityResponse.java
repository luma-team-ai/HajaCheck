package com.hajacheck.core.facility.dto;

import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.entity.FacilityInitialGrade;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 시설물 응답 DTO — Entity 직접 노출 금지(§0).
 */
public record FacilityResponse(
        Long id,
        String name,
        String type,
        String address,
        BigDecimal latitude,
        BigDecimal longitude,
        Integer builtYear,
        String scale,
        Integer inspectionCycleMonths,
        LocalDate nextInspectionDueAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        FacilityInitialGrade initialGrade,
        Long assigneeUserId,
        String memo,
        // 시설물 상세→하자 오버레이 직행(HAJA-434 갭1) — 대표(최신) 하자 id, 하자가 없으면 null.
        Long latestDefectId
) {
    public static FacilityResponse from(Facility facility) {
        return from(facility, null);
    }

    public static FacilityResponse from(Facility facility, Long latestDefectId) {
        return new FacilityResponse(
                facility.getId(),
                facility.getName(),
                facility.getType(),
                facility.getAddress(),
                facility.getLatitude(),
                facility.getLongitude(),
                facility.getBuiltYear(),
                facility.getScale(),
                facility.getInspectionCycleMonths(),
                facility.getNextInspectionDueAt(),
                facility.getCreatedAt(),
                facility.getUpdatedAt(),
                facility.getInitialGrade(),
                facility.getAssigneeUserId(),
                facility.getMemo(),
                latestDefectId
        );
    }
}
