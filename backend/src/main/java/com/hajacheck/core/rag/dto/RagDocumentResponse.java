package com.hajacheck.core.rag.dto;

import com.hajacheck.core.rag.entity.RagDocument;
import com.hajacheck.core.rag.entity.RagDocumentSourceType;
import com.hajacheck.core.rag.entity.RagDocumentVerificationStatus;
import com.hajacheck.core.rag.entity.RagEmbeddingStatus;
import com.hajacheck.core.rag.entity.RagTargetCollection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * RAG 문서 응답 — 엔티티 직접 노출 금지 원칙(#22/HAJA-35). fileUrl(내부 저장키)은 응답에 담지 않는다
 * (관리자 화면은 원본 다운로드 링크가 아니라 메타데이터·임베딩 상태만 다룬다 — 범위 밖).
 */
public record RagDocumentResponse(
        Long id,
        String title,
        RagDocumentSourceType sourceType,
        RagTargetCollection targetCollection,
        LocalDate effectiveDate,
        String publisher,
        LocalDate authoredAt,
        RagDocumentVerificationStatus verificationStatus,
        RagEmbeddingStatus embeddingStatus,
        Integer chunkCount,
        Instant embeddedAt,
        LocalDateTime createdAt) {

    public static RagDocumentResponse from(RagDocument document) {
        return new RagDocumentResponse(
                document.getId(),
                document.getTitle(),
                document.getSourceType(),
                document.getTargetCollection(),
                document.getEffectiveDate(),
                document.getPublisher(),
                document.getAuthoredAt(),
                document.getVerificationStatus(),
                document.getEmbeddingStatus(),
                document.getChunkCount(),
                document.getEmbeddedAt(),
                document.getCreatedAt());
    }
}
