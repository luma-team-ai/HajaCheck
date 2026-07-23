package com.hajacheck.core.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 프론트/서비스 기대 응답 — FastAPI {@code AIResponse.data}({@code {"chunk_count": n}})를 그대로 매핑한다.
 */
public record RagEmbedResponse(@JsonProperty("chunk_count") int chunkCount) {
}
