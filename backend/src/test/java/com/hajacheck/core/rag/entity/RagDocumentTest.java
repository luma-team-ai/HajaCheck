package com.hajacheck.core.rag.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RagDocumentTest {

    @Test
    void upload_nullable검증상태를보존하고대기상태로생성() {
        RagDocument document = RagDocument.upload(
                "시설물 안전법", RagDocumentSourceType.LAW, RagTargetCollection.REGULATIONS,
                LocalDate.of(2026, 1, 1), "국토교통부", null, null,
                "https://files.example/law.pdf");

        assertThat(document.getVerificationStatus()).isNull();
        assertThat(document.getEmbeddingStatus()).isEqualTo(RagEmbeddingStatus.PENDING);
        assertThat(document.getTargetCollection()).isEqualTo(RagTargetCollection.REGULATIONS);
    }

    @Test
    void completeEmbedding_청크수와완료시각을기록() {
        RagDocument document = RagDocument.upload(
                "하자 지식", RagDocumentSourceType.GUIDELINE, RagTargetCollection.DEFECT_KB,
                null, null, LocalDate.of(2026, 7, 16),
                RagDocumentVerificationStatus.UNVERIFIED, "https://files.example/kb.pdf");

        document.startEmbedding();
        document.completeEmbedding(12);

        assertThat(document.getEmbeddingStatus()).isEqualTo(RagEmbeddingStatus.DONE);
        assertThat(document.getChunkCount()).isEqualTo(12);
        assertThat(document.getEmbeddedAt()).isNotNull();
    }

    @Test
    void embedding_잘못된상태전이를거부() {
        RagDocument document = RagDocument.upload(
                "검증 문서", RagDocumentSourceType.LAW, RagTargetCollection.REGULATIONS,
                null, null, null, null, "https://files.example/doc.pdf");

        assertThatThrownBy(() -> document.completeEmbedding(1))
                .isInstanceOf(IllegalStateException.class);
        document.startEmbedding();
        document.completeEmbedding(1);
        assertThatThrownBy(document::failEmbedding)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void completeEmbedding_음수청크수이면임베딩중상태를유지하고예외() {
        RagDocument document = RagDocument.upload(
                "검증 문서", RagDocumentSourceType.LAW, RagTargetCollection.REGULATIONS,
                null, null, null, null, "https://files.example/doc.pdf");
        document.startEmbedding();

        assertThatThrownBy(() -> document.completeEmbedding(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(document.getEmbeddingStatus()).isEqualTo(RagEmbeddingStatus.EMBEDDING);
        assertThat(document.getChunkCount()).isNull();
        assertThat(document.getEmbeddedAt()).isNull();
    }

    @Test
    void verify_미검증문서를검증하고재호출은멱등() {
        RagDocument document = RagDocument.upload(
                "검증 문서", RagDocumentSourceType.LAW, RagTargetCollection.REGULATIONS,
                null, null, null, RagDocumentVerificationStatus.UNVERIFIED,
                "https://files.example/doc.pdf");

        document.verify();
        document.verify();

        assertThat(document.getVerificationStatus()).isEqualTo(RagDocumentVerificationStatus.VERIFIED);
    }
}
