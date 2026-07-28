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
        Long latestDefectId,
        // 시설물 목록/상세 대표 사진(HAJA-367/#670) — /api/media/{id}/thumbnail 경로, 사진이 없으면 null.
        // Media.thumbnailUrl(저장키)을 그대로 반환하지 않는다(MediaResponse와 동일 원칙 — 인가된
        // 컨트롤러 엔드포인트를 통해서만 서빙).
        String thumbnailUrl,
        // 시설물 카드 목록 "최근 점검 MM.dd"(HAJA-514/#1074, HAJA-368 선행) — 가장 최근 점검의
        // inspectionDate, 점검 이력이 없으면 null. FacilityStatusResponse.lastInspectedAt과
        // 동일 조회(inspectionRepository.findLatestByFacilityIds)를 재사용한다.
        LocalDate lastInspectedAt
) {
    public static FacilityResponse from(Facility facility) {
        return from(facility, null, null, null);
    }

    public static FacilityResponse from(Facility facility, Long latestDefectId) {
        return from(facility, latestDefectId, null, null);
    }

    public static FacilityResponse from(
            Facility facility, Long latestDefectId, String thumbnailUrl, LocalDate lastInspectedAt) {
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
                latestDefectId,
                thumbnailUrl,
                lastInspectedAt
        );
    }
}
