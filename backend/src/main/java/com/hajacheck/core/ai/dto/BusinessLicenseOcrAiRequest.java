package com.hajacheck.core.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * FastAPI {@code POST /ai/business-license-ocr} 요청 바디(#557 / HAJA-169). image_base64 경로만
 * 사용한다(file_ref 는 AI 서버 쪽도 아직 미구현 — seam only).
 */
public record BusinessLicenseOcrAiRequest(@JsonProperty("image_base64") String imageBase64) {
}
