package com.hajacheck.core.rag.entity;

/** PostgreSQL {@code rag_embedding_status_type}과 일치하는 임베딩 처리 상태. */
public enum RagEmbeddingStatus {
    PENDING,
    EMBEDDING,
    DONE,
    FAILED
}
