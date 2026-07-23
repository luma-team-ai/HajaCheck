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
        String grade) {
}
