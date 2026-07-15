package com.hajacheck.core.dashboard.dto;

import com.hajacheck.core.defect.entity.DefectGrade;

/**
 * GET /api/dashboard/grade-distribution 응답 항목 — frontend GradeDistributionItem { grade, percent } 과 1:1.
 * grade 는 항상 A~E 5개 전부 반환한다(집계가 없는 등급은 percent=0).
 */
public record GradeDistributionResponse(String grade, double percent) {

    public static GradeDistributionResponse of(DefectGrade grade, long count, long total) {
        double percent = total == 0 ? 0.0 : Math.round(count * 1000.0 / total) / 10.0;
        return new GradeDistributionResponse(grade.name(), percent);
    }
}
