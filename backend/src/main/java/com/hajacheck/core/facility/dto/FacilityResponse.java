package com.hajacheck.core.facility.dto;

import com.hajacheck.core.facility.entity.Facility;
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
        LocalDateTime updatedAt
) {
    public static FacilityResponse from(Facility facility) {
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
                facility.getUpdatedAt()
        );
    }
}
