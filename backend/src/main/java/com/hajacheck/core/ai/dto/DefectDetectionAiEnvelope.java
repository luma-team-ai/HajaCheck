package com.hajacheck.core.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * FastAPI {@code POST /ai/detect-defects} 원본 응답 envelope(dev-05-04) — 다른 /ai/* 프록시와
 * 동일한 success/data/error 구조. data는 {@code {"detections": [...]}} 형태.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DefectDetectionAiEnvelope(
        boolean success,
        Data data,
        ErrorBody error) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(List<DetectedDefectItem> detections) {
    }

    public record ErrorBody(String code, String message) {
    }
}
