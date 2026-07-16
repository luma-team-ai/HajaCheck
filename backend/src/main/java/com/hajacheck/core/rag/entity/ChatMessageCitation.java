package com.hajacheck.core.rag.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** RAG 답변 메시지가 인용한 PostgreSQL 문서와 Chroma 청크 참조. */
@Entity
@Getter
@Table(
        name = "chat_message_citations",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_chat_message_citations_message_document_chunk",
                columnNames = {"message_id", "document_id", "chunk_ref"}),
        indexes = {
                @Index(name = "idx_chat_message_citations_message", columnList = "message_id"),
                @Index(name = "idx_chat_message_citations_document", columnList = "document_id")
        })
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessageCitation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** counsel 도메인의 ChatMessage 식별자. 도메인 경계를 넘어 Entity를 직접 참조하지 않는다. */
    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", insertable = false, updatable = false)
    private RagDocument document;

    /** PostgreSQL FK가 아닌 Chroma 외부 저장소의 청크 식별자. */
    @Column(name = "chunk_ref", nullable = false, length = 100)
    private String chunkRef;

    @Column(nullable = false, columnDefinition = "text")
    private String locator;

    @Column(nullable = false, columnDefinition = "text")
    private String snippet;

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Builder(access = AccessLevel.PRIVATE)
    private ChatMessageCitation(Long messageId, Long documentId, String chunkRef,
                                String locator, String snippet) {
        this.messageId = messageId;
        this.documentId = documentId;
        this.chunkRef = chunkRef;
        this.locator = locator;
        this.snippet = snippet;
    }

    public static ChatMessageCitation create(Long messageId, Long documentId, String chunkRef,
                                             String locator, String snippet) {
        return ChatMessageCitation.builder()
                .messageId(messageId)
                .documentId(documentId)
                .chunkRef(chunkRef)
                .locator(locator)
                .snippet(snippet)
                .build();
    }
}
