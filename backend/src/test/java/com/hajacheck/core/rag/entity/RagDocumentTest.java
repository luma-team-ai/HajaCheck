package com.hajacheck.core.rag.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RagDocumentTest {

    @Test
    void upload_allowsNullableVerificationMetadata() {
        RagDocument document = RagDocument.upload(
                "safety law", RagDocumentSourceType.LAW, RagTargetCollection.REGULATIONS,
                LocalDate.of(2026, 1, 1), "authority", null, null,
                "https://files.example/law.pdf");

        assertThat(document.getVerificationStatus()).isNull();
        assertThat(document.getEmbeddingStatus()).isEqualTo(RagEmbeddingStatus.PENDING);
        assertThat(document.getTargetCollection()).isEqualTo(RagTargetCollection.REGULATIONS);
    }

    @Test
    void embedding_recordsChunkCountAndCompletionTime() {
        RagDocument document = RagDocument.upload(
                "guideline", RagDocumentSourceType.GUIDELINE, RagTargetCollection.DEFECT_KB,
                null, null, LocalDate.of(2026, 7, 16),
                RagDocumentVerificationStatus.UNVERIFIED, "https://files.example/kb.pdf");

        document.startEmbedding();
        document.completeEmbedding(12);

        assertThat(document.getEmbeddingStatus()).isEqualTo(RagEmbeddingStatus.DONE);
        assertThat(document.getChunkCount()).isEqualTo(12);
        assertThat(document.getEmbeddedAt()).isNotNull();
    }

    @Test
    void embedding_rejectsInvalidTransitions() {
        RagDocument document = RagDocument.upload(
                "document", RagDocumentSourceType.LAW, RagTargetCollection.REGULATIONS,
                null, null, null, null, "https://files.example/doc.pdf");

        assertThatThrownBy(() -> document.completeEmbedding(1))
                .isInstanceOf(IllegalStateException.class);
        document.startEmbedding();
        document.completeEmbedding(1);
        assertThatThrownBy(document::failEmbedding)
                .isInstanceOf(IllegalStateException.class);
    }
}
