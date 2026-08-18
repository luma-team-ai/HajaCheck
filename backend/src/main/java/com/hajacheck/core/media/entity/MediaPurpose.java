package com.hajacheck.core.media.entity;

/**
 * 미디어 용도 — DDL media.purpose(V41, #1641).
 *
 * <p>원본 촬영 사진 업로드와 조치 후 사진 업로드가 동일한 {@code POST /api/inspections/{id}/media}
 * 엔드포인트 + media 테이블을 공유하기 때문에, 구분값이 없으면 분석결과뷰어(#803)와 AI 재분석 대상
 * 조회가 조치 후 사진까지 원본 촬영 사진으로 오인해 노출/재분석한다.
 */
public enum MediaPurpose {
    /** 원본 촬영 사진 — 분석 대상, 분석결과뷰어 노출 대상. 업로드 시 미지정하면 기본값으로 채워진다. */
    INSPECTION_SOURCE,
    /** 조치 후 사진(하자 조치 결과 등록 첨부) — 분석·분석결과뷰어 제외. */
    DEFECT_ACTION
}
