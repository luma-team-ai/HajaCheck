package com.hajacheck.core.inspection.dto;

import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import java.time.LocalDate;

/**
 * GET /api/inspections 목록 항목(HAJA-393/#725) — 하자 목록 화면 개편의 "①점검 단위 목록"에 대응한다.
 * assignedInspectorId 는 점검 회차 생성 시 배정된 담당자(Inspection 엔티티 필드)이며, 대시보드
 * {@code RecentInspectionResponse}가 표시하는 createdBy 기반 "inspector"와는 의미가 다르다 — 이 화면은
 * 점검 목록 자체이므로 실제 배정된 담당자를 노출한다.
 */
public record InspectionListItemResponse(
        Long id,
        Long facilityId,
        String facilityName,
        Integer roundNo,
        LocalDate inspectionDate,
        Long assignedInspectorId,
        String assignedInspectorName,
        InspectionStatus status,
        long defectCount
) {

    public static InspectionListItemResponse from(
            Inspection inspection, String facilityName, String assignedInspectorName, long defectCount) {
        return new InspectionListItemResponse(
                inspection.getId(),
                inspection.getFacilityId(),
                facilityName,
                inspection.getRoundNo(),
                inspection.getInspectionDate(),
                inspection.getAssignedInspectorId(),
                assignedInspectorName,
                inspection.getStatus(),
                defectCount);
    }
}
