package com.hajacheck.core.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 프론트/서비스 기대 응답 — FastAPI {@code AIResponse.data}
 * ({@code {"chunk_count": n, "embed_batch_id": "..."}})를 그대로 매핑한다.
 *
 * @param chunkCount   청킹 직후 예상 최종 청크 수
 * @param embedBatchId 이번 임베딩 배치 식별자(#1393) — FastAPI 배경 임베딩이 각 청크 메타데이터에
 *                     심는 값이다. 폴러가 embedding-status 응답의 값과 이 값을 대조해, 청크 수만
 *                     같고 실제로는 옛 청크가 남아 있는 재임베딩을 거짓 완료로 확정하지 않게 한다.
 */
public record RagEmbedResponse(@JsonProperty("chunk_count") int chunkCount,
                               @JsonProperty("embed_batch_id") String embedBatchId) {
}
