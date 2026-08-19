package com.hajacheck.core.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * FastAPI {@code /ai/detect-defects} 탐지 1건 — {@code type}은 Spring DefectType enum 이름
 * (CRACK/SPALLING/REBAR_EXPOSURE) 그대로, bbox는 0~1 정규화 좌표(프론트 DefectOverlay 계약과 동일),
 * {@code grade}는 A~E(FastAPI가 하자_심각도_등급_규칙.md 기준으로 이미 계산해 내려준다 — Spring 재계산 금지).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DetectedDefectItem(
        String type,
        @JsonProperty("bbox_x") Double bboxX,
        @JsonProperty("bbox_y") Double bboxY,
        @JsonProperty("bbox_w") Double bboxW,
        @JsonProperty("bbox_h") Double bboxH,
        Double confidence,
        String grade,
        @JsonProperty("area_ratio") Double areaRatio,
        // 균열만 — 카드 기준물로 환산한 폭(mm). 카드 미검출 또는 폭 0.7mm 미만이면 null(#1487/#1547).
        @JsonProperty("width_mm") Double widthMm,
        // SPALLING/REBAR_EXPOSURE만 — 카드 기준물로 환산한 결함 실측 면적(mm², #1658/#1668 additive).
        // 카드 미검출 또는 CRACK 타입이면 null.
        @JsonProperty("area_mm2") Double areaMm2,
        // area_mm2 기반 mm² 참고 등급(A~E, #1682/#1683 additive) — area_mm2가 없으면(카드 미검출 또는
        // CRACK 타입) null. FastAPI가 이미 계산해 내려주므로 Spring 재계산 금지(grade와 동일 원칙),
        // 본등급 grade와는 무관한 참고값이라 enum 변환 없이 문자열 그대로 저장한다.
        @JsonProperty("area_mm2_reference_grade") String areaMm2ReferenceGrade) {
}
