package com.hajacheck.core.statistics.dto;

public record FacilitySummaryResponse(
        Long facilityId,
        String facilityName,
        String facilityType,
        long totalDefects,
        String latestGrade,
        String lastInspectedAt
) {
}
