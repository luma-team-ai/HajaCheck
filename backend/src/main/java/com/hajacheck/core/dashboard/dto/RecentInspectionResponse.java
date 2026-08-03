package com.hajacheck.core.dashboard.dto;

import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import java.time.LocalDate;

/**
 * GET /api/dashboard/recent-inspections 응답 항목 — frontend RecentInspectionItem
 * { id, facilityName, inspectedAt, inspector, defectCount, status } 과 1:1.
 *
 * <p>status 는 DB의 7단계(inspection_status_type)를 화면 5단계로 축약해서 매핑한다:
 * CREATED/UPLOADING/ANALYZING → 분석중, ANALYZED → 검수대기, FAILED → 분석실패, REVIEWED → 검수확정, REPORTED → 완료.
 * REVIEWED 라벨은 KPI 카드와 통일해 "검수확정"을 쓴다(HAJA-499/#1044) — 구 라벨 "조치대기" 폐기.
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
            case FAILED -> "분석실패";
            case REVIEWED -> "검수확정";
            case REPORTED -> "완료";
        };
    }
}
