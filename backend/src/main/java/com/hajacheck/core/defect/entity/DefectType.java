package com.hajacheck.core.defect.entity;

/**
 * 결함 유형 — DDL defect_type(균열/박리·박락/누수·백태/철근노출/도장손상).
 */
public enum DefectType {
    CRACK,
    SPALLING,
    LEAK_EFFLORESCENCE,
    REBAR_EXPOSURE,
    PAINT_DAMAGE;

    /**
     * 한글 라벨(DDL 코멘트 기준) — PendingPriorityResponse.typeLabel 과 동일 매핑.
     * AI 주간 브리핑(#248) top_defect_type 집계에서 재사용한다.
     */
    public String label() {
        return switch (this) {
            case CRACK -> "균열";
            case SPALLING -> "박리·박락";
            case LEAK_EFFLORESCENCE -> "누수·백태";
            case REBAR_EXPOSURE -> "철근 노출";
            case PAINT_DAMAGE -> "도장 손상";
        };
    }
}
