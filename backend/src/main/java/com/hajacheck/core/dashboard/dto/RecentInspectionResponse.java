package com.hajacheck.core.dashboard.dto;

import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import java.time.LocalDate;

/**
 * GET /api/dashboard/recent-inspections 응답 항목 — frontend RecentInspectionItem
 * { id, facilityName, inspectedAt, inspector, defectCount, status } 과 1:1.
 *
 * <p>status 는 DB의 6단계(inspection_status_type)를 화면 4단계로 축약해서 매핑한다:
 * CREATED/UPLOADING/ANALYZING → 분석중, ANALYZED → 검수대기, REVIEWED → 조치대기, REPORTED → 완료.
 */
public record RecentInspectionResponse(
        Long id,
        String facilityName,
        LocalDate inspectedAt,
        String inspector,
        long defectCount,
        String status
) {

    public static RecentInspectionResponse from(Inspection inspection, String facilityName,
                                                 String inspectorName, long defectCount) {
        return new RecentInspectionResponse(
                inspection.getId(),
                facilityName,
                inspection.getInspectionDate(),
                inspectorName,
                defectCount,
                statusLabel(inspection.getStatus()));
    }

    private static String statusLabel(InspectionStatus status) {
        return switch (status) {
            case CREATED, UPLOADING, ANALYZING -> "분석중";
            case ANALYZED -> "검수대기";
            case REVIEWED -> "조치대기";
            case REPORTED -> "완료";
        };
    }
}
