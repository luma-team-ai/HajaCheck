package com.hajacheck.core.defect.repository;

import com.hajacheck.core.defect.entity.DefectType;

/**
 * AI 주간 브리핑(#248) top_defect_type 집계 — type 별 결함 수 프로젝션.
 */
public interface DefectTypeCountProjection {
    DefectType getType();

    long getCnt();
}
