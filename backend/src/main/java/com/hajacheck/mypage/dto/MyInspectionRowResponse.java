package com.hajacheck.mypage.dto;

import com.hajacheck.core.inspection.entity.Inspection;
import java.time.LocalDate;

/**
 * GET /api/me/inspections 목록 항목(#844, handoff §2-2). roundNo는 정수 그대로 반환하고
 * '24-03' 같은 조립 표기는 프론트 책임이다 — inspectionDate도 ISO {@code yyyy-MM-dd} 그대로
 * 반환하고 '2024.03.15' 표기는 프론트가 담당한다.
 */
public record MyInspectionRowResponse(
        Long id,
        String facilityName,
        Integer roundNo,
        LocalDate inspectionDate,
        MyInspectionRole role,
        long defectCount,
        MyInspectionDisplayStatus status) {

    public static MyInspectionRowResponse from(
            Inspection inspection, String facilityName, long defectCount, Long requesterUserId) {
        MyInspectionRole role = inspection.getAssignedInspectorId().equals(requesterUserId)
                ? MyInspectionRole.INSPECTOR
                : MyInspectionRole.OWNER;
        return new MyInspectionRowResponse(
                inspection.getId(),
                facilityName,
                inspection.getRoundNo(),
                inspection.getInspectionDate(),
                role,
                defectCount,
                MyInspectionDisplayStatus.from(inspection.getStatus()));
    }
}
