package com.hajacheck.core.facility.dto;

import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.entity.FacilityInitialGrade;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 시설물 응답 DTO — Entity 직접 노출 금지(§0).
 *
 * <p>photoUrls 는 별도 테이블(facility_photos)에서 조회한 값이라 Facility 엔티티만으로는 채울 수 없어
 * {@link #from(Facility, List)} 가 별도 인자로 받는다(#628 / HAJA-347).
 */
public record FacilityResponse(
        Long id,
        Long ownerId,
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
        List<String> photoUrls,
        FacilityInitialGrade initialGrade,
        Long assigneeUserId,
        String memo
) {
    public static FacilityResponse from(Facility facility, List<String> photoUrls) {
        return new FacilityResponse(
                facility.getId(),
                facility.getOwnerId(),
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
                photoUrls,
                facility.getInitialGrade(),
                facility.getAssigneeUserId(),
                facility.getMemo()
        );
    }
}
