package com.hajacheck.core.defect.repository;

import com.hajacheck.core.defect.entity.DefectGrade;

/**
 * 대시보드 등급 분포(HAJA-17) — grade 별 결함 수 집계 프로젝션.
 */
public interface GradeCountProjection {
    DefectGrade getGrade();

    long getCnt();
}
