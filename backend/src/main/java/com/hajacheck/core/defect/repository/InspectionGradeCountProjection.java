package com.hajacheck.core.defect.repository;

import com.hajacheck.core.defect.entity.DefectGrade;

/**
 * 점검 목록 등급분포(#893/HAJA-458) — 점검(회차)별 등급 분포를 배치 조회하기 위한 프로젝션.
 * {@link InspectionGradeProjection}(마이페이지 gradeDots)은 존재 여부만 필요해 count가 없고,
 * {@link GradeCountProjection}(대시보드)은 inspectionId 없이 전체를 하나로 합산한다 — 이 화면은
 * "점검별 등급별 건수"가 모두 필요해 별도로 둔다.
 */
public interface InspectionGradeCountProjection {
    Long getInspectionId();

    DefectGrade getGrade();

    long getCnt();
}
