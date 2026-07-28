package com.hajacheck.core.inspection.dto;

import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.entity.InspectionType;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 점검 목록의 수동·자연어 공통 필터 요청.
 */
public record InspectionListFilterRequest(
        Long facilityId,
        List<InspectionStatus> status,
        List<InspectionType> inspectionType,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inspectionDateFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inspectionDateTo,
        @Min(1) Integer roundNoMin,
        @Min(1) Integer roundNoMax,
        @Min(0) Long defectCountMin,
        @Min(0) Long defectCountMax,
        List<DefectType> defectType,
        List<DefectGrade> defectGrade,
        List<DefectStatus> defectStatus) {
}
