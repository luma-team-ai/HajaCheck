package com.hajacheck.core.inspection.dto;

import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import java.time.LocalDate;
import java.util.Map;

/**
 * GET /api/inspections 목록 항목(HAJA-393/#725, gradeDistribution·assigneeName은 #893/HAJA-458). 하자
 * 목록 화면 개편의 "①점검 단위 목록"에 대응한다. assignedInspectorId 는 점검 회차 생성 시 배정된
 * 담당자(Inspection 엔티티 필드)이며, 대시보드 {@code RecentInspectionResponse}가 표시하는 createdBy
 * 기반 "inspector"와는 의미가 다르다 — 이 화면은 점검 목록 자체이므로 실제 배정된 담당자를 노출한다.
 * assigneeName은 프론트 InspectionListItem.assigneeName과 이름을 맞춘 필드명이다(#893 계약 정합화 —
 * 과거 assignedInspectorName 이었음). gradeDistribution 은 A~E 5개 키를 항상 포함하며(값 없는 등급은 0),
 * BriefingStatsService.gradeDistribution() 과 동일한 "기본값 세팅 후 덮어쓰기" 패턴을 따른다.
 */
public record InspectionListItemResponse(
        Long id,
        Long facilityId,
        String facilityName,
        Integer roundNo,
        LocalDate inspectionDate,
        Long assignedInspectorId,
        String assigneeName,
        InspectionStatus status,
        long defectCount,
        Map<String, Long> gradeDistribution
) {

    public static InspectionListItemResponse from(
            Inspection inspection, String facilityName, String assigneeName, long defectCount,
            Map<String, Long> gradeDistribution) {
        return new InspectionListItemResponse(
                inspection.getId(),
                inspection.getFacilityId(),
                facilityName,
                inspection.getRoundNo(),
                inspection.getInspectionDate(),
                inspection.getAssignedInspectorId(),
                assigneeName,
                inspection.getStatus(),
                defectCount,
                gradeDistribution);
    }
}
