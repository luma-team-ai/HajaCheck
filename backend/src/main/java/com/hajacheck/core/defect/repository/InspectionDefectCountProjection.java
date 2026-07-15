package com.hajacheck.core.defect.repository;

/**
 * 대시보드 최근 점검 목록(HAJA-17) — 점검별 결함 건수 집계 프로젝션.
 */
public interface InspectionDefectCountProjection {
    Long getInspectionId();

    long getCnt();
}
