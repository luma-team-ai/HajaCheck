package com.hajacheck.core.rag.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** PostgreSQL 메타데이터와 Chroma 임베딩 상태를 연결하는 RAG 문서. */
@Entity
@Getter
@Table(name = "rag_documents", indexes = {
        @Index(name = "idx_rag_documents_embedding_status", columnList = "embedding_status"),
        @Index(name = "idx_rag_documents_target_collection", columnList = "target_collection")
})
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RagDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 300)
    private String title;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "source_type", columnDefinition = "rag_doc_source_type", nullable = false)
    private RagDocumentSourceType sourceType;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "target_collection", columnDefinition = "rag_target_collection_type", nullable = false)
    private RagTargetCollection targetCollection;

    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    @Column(length = 200)
    private String publisher;

    @Column(name = "authored_at")
    private LocalDate authoredAt;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "verification_status", columnDefinition = "rag_doc_verification_status_type")
    private RagDocumentVerificationStatus verificationStatus;

    @Column(name = "file_url", nullable = false, length = 500)
    private String fileUrl;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "embedding_status", columnDefinition = "rag_embedding_status_type", nullable = false)
    private RagEmbeddingStatus embeddingStatus;

    @Column(name = "chunk_count")
    private Integer chunkCount;

    @Column(name = "embedded_at")
    private Instant embeddedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Builder(access = AccessLevel.PRIVATE)
    private RagDocument(String title, RagDocumentSourceType sourceType,
                        RagTargetCollection targetCollection, LocalDate effectiveDate,
                        String publisher, LocalDate authoredAt,
                        RagDocumentVerificationStatus verificationStatus, String fileUrl,
                        RagEmbeddingStatus embeddingStatus, Integer chunkCount, Instant embeddedAt) {
        this.title = title;
        this.sourceType = sourceType;
        this.targetCollection = targetCollection;
        this.effectiveDate = effectiveDate;
        this.publisher = publisher;
        this.authoredAt = authoredAt;
        this.verificationStatus = verificationStatus;
        this.fileUrl = fileUrl;
        this.embeddingStatus = embeddingStatus == null ? RagEmbeddingStatus.PENDING : embeddingStatus;
        this.chunkCount = chunkCount;
        this.embeddedAt = embeddedAt;
    }

    public static RagDocument upload(String title, RagDocumentSourceType sourceType,
                                     RagTargetCollection targetCollection, LocalDate effectiveDate,
                                     String publisher, LocalDate authoredAt,
                                     RagDocumentVerificationStatus verificationStatus, String fileUrl) {
        return RagDocument.builder()
                .title(title)
                .sourceType(sourceType)
                .targetCollection(targetCollection)
                .effectiveDate(effectiveDate)
                .publisher(publisher)
                .authoredAt(authoredAt)
                .verificationStatus(verificationStatus)
                .fileUrl(fileUrl)
                .embeddingStatus(RagEmbeddingStatus.PENDING)
                .build();
    }

    public void startEmbedding() {
        requireEmbeddingStatus("startEmbedding", RagEmbeddingStatus.PENDING, RagEmbeddingStatus.FAILED);
        this.embeddingStatus = RagEmbeddingStatus.EMBEDDING;
    }

    public void completeEmbedding(int chunkCount) {
        requireEmbeddingStatus("completeEmbedding", RagEmbeddingStatus.EMBEDDING);
        this.embeddingStatus = RagEmbeddingStatus.DONE;
        this.chunkCount = chunkCount;
        this.embeddedAt = Instant.now();
    }

    public void failEmbedding() {
        requireEmbeddingStatus("failEmbedding", RagEmbeddingStatus.EMBEDDING);
        this.embeddingStatus = RagEmbeddingStatus.FAILED;
    }

    private void requireEmbeddingStatus(String action, RagEmbeddingStatus... allowed) {
        for (RagEmbeddingStatus candidate : allowed) {
            if (this.embeddingStatus == candidate) {
                return;
            }
        }
        throw new IllegalStateException(
                "%s 불가: 현재 임베딩 상태=%s, 허용 상태=%s"
                        .formatted(action, this.embeddingStatus, Arrays.toString(allowed)));
    }

    public void verify() {
        this.verificationStatus = RagDocumentVerificationStatus.VERIFIED;
    }
}
