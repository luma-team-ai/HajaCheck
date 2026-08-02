package com.hajacheck.core.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * FastAPI {@code GET /ai/rag-documents/{doc_id}/embedding-status} 응답 데이터(#1328) —
 * {@code AIResponse.data}({@code {"chunk_count": n}})를 그대로 매핑한다. RagEmbedResponse와 필드가
 * 같지만 의미가 다르다(RagEmbedResponse=청킹 직후 예상 청크 수, 이 record=실제 Chroma에 적재된
 * 현재 청크 수) — 폴러가 둘을 비교해야 하므로 타입 혼동을 막기 위해 별도 record로 분리한다.
 */
public record RagEmbeddingStatusResponse(@JsonProperty("chunk_count") int chunkCount) {
}
