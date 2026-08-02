package com.hajacheck.core.rag.service;

import com.hajacheck.core.ai.dto.RagEmbeddingStatusResponse;

/**
 * "배경 임베딩이 이번 배치로 실제 완료됐는가" 판정을 한 곳에 모은 헬퍼(#1393 리뷰 2차 P2) —
 * {@link RagEmbeddingCompletionPoller}(폴링 중 확정)와
 * {@link com.hajacheck.core.rag.scheduler.RagEmbeddingStaleReconciler}(폴러 상한 이후 마지막 재확인)가
 * 같은 규칙을 써야 한쪽만 완료로 보고 다른 쪽이 FAILED로 덮는 불일치가 생기지 않는다.
 */
public final class RagEmbeddingCompletionCheck {

    private RagEmbeddingCompletionCheck() {
    }

    /**
     * @param status             embedding-status 응답 데이터(널이면 미완료로 본다)
     * @param expectedChunkCount 청킹 단계에서 FastAPI가 즉시 돌려준 예상 청크 수
     * @param expectedBatchId    이번 임베딩 배치 식별자
     * @return 이번 배치가 Chroma에 온전히 적재됐다고 볼 수 있으면 true
     */
    public static boolean isComplete(RagEmbeddingStatusResponse status, int expectedChunkCount,
                                     String expectedBatchId) {
        if (status == null) {
            return false;
        }
        if (expectedChunkCount == 0) {
            // 빈/추출불가 텍스트는 청크가 0개라 ai-server ingest_document가 add_texts 자체를 하지 않고
            // (`if not chunks: return 0`), 따라서 어떤 청크에도 embed_batch_id가 실리지 않는다.
            // 배치 식별자를 요구하면 이런 문서는 영원히 완료로 확정되지 못하고 FAILED로 귀결된다
            // (#1393 리뷰 2차 P2 — 이전에는 chunk_count=0이면 즉시 DONE이던 동작의 회귀).
            // 실제 적재 청크가 0개인지만 확인하고 배치 대조는 건너뛴다.
            return status.chunkCount() == 0;
        }
        return status.chunkCount() == expectedChunkCount
                && isThisBatch(status.embedBatchId(), expectedBatchId);
    }

    /**
     * embedding-status가 돌려준 배치 식별자가 이번 요청의 배치와 같은지 — 재임베딩 중에는 옛 배치 청크가
     * 그대로 남아 있어(add_texts는 upsert라 delete 없이 덮어쓴다) 청크 수만으로는 완료를 구분할 수 없다.
     * 응답이 null이면(옛/새 배치가 섞여 있거나 배치 식별자 이전 버전의 ai-server) 완료로 보지 않는다.
     */
    private static boolean isThisBatch(String actualBatchId, String expectedBatchId) {
        return expectedBatchId != null && expectedBatchId.equals(actualBatchId);
    }
}
