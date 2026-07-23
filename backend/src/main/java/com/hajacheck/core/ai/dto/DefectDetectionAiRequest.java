package com.hajacheck.core.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * FastAPI {@code POST /ai/detect-defects} 요청 바디(dev-05-04). 이미지 1장당 1회 호출한다
 * (BusinessLicenseOcrAiRequest와 동일한 image_base64 단일 필드 계약).
 */
public record DefectDetectionAiRequest(@JsonProperty("image_base64") String imageBase64) {
}
