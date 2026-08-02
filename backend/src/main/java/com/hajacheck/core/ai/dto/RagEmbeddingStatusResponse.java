package com.hajacheck.core.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * FastAPI {@code GET /ai/rag-documents/{doc_id}/embedding-status} 응답 데이터(#1328) —
 * {@code AIResponse.data}({@code {"chunk_count": n}})를 그대로 매핑한다. RagEmbedResponse와 필드가
 * <p>{@code embed_batch_id}는 적재된 청크 전부가 같은 배치에서 나왔을 때만 채워지고, 재임베딩 도중
 * 옛 배치와 새 배치 청크가 섞여 있으면 null이다(#1393) — 폴러는 이 값이 이번 요청의 배치와 일치할
 * 때만 완료로 확정한다.
 *
 * <p>RagEmbedResponse와 필드가 같지만 의미가 다르다(RagEmbedResponse=청킹 직후 예상 청크 수, 이 record=실제 Chroma에 적재된
 * 현재 청크 수) — 폴러가 둘을 비교해야 하므로 타입 혼동을 막기 위해 별도 record로 분리한다.
 */
public record RagEmbeddingStatusResponse(@JsonProperty("chunk_count") int chunkCount,
                                         @JsonProperty("embed_batch_id") String embedBatchId) {
}
