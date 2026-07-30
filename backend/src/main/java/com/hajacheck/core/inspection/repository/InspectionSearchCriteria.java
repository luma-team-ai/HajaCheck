package com.hajacheck.core.inspection.repository;

import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.entity.InspectionType;
import java.time.LocalDate;
import java.util.List;

/**
 * 회사 스코프가 결합된 점검 목록 조회 조건.
 */
public record InspectionSearchCriteria(
        Long companyId,
        Long facilityId,
        List<InspectionStatus> statuses,
        List<InspectionType> inspectionTypes,
        LocalDate inspectionDateFrom,
        LocalDate inspectionDateTo,
        Integer roundNoMin,
        Integer roundNoMax,
        Long defectCountMin,
        Long defectCountMax,
        List<DefectType> defectTypes,
        List<DefectGrade> defectGrades,
        List<DefectStatus> defectStatuses) {
}
