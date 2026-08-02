package com.hajacheck.core.rag.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
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
    void restartEmbedding_완료문서를곧바로임베딩중으로전이() {
        RagDocument document = RagDocument.upload(
                "시설물 안전법", RagDocumentSourceType.LAW, RagTargetCollection.REGULATIONS,
                null, null, null, null, "https://files.example/law.pdf");
        document.startEmbedding();
        document.completeEmbedding(5);

        document.restartEmbedding();

        // resetForReEmbed()+startEmbedding() 2단계였던 이전 구현과 달리 PENDING을 거치지 않고
        // 곧바로 EMBEDDING으로 전이한다(code-review P2 — 중간 PENDING이 동시 조회 시 오해를 유발).
        assertThat(document.getEmbeddingStatus()).isEqualTo(RagEmbeddingStatus.EMBEDDING);
        document.completeEmbedding(7);
        assertThat(document.getChunkCount()).isEqualTo(7);
    }

    @Test
    void restartEmbedding_실패문서도곧바로임베딩중으로전이() {
        RagDocument document = RagDocument.upload(
                "하자 지식", RagDocumentSourceType.GUIDELINE, RagTargetCollection.DEFECT_KB,
                null, null, null, null, "https://files.example/kb.pdf");
        document.startEmbedding();
        document.failEmbedding();

        document.restartEmbedding();

        assertThat(document.getEmbeddingStatus()).isEqualTo(RagEmbeddingStatus.EMBEDDING);
    }

    @Test
    void restartEmbedding_임베딩중에는거부_동시재임베딩레이스방지() {
        RagDocument document = RagDocument.upload(
                "시설물 안전법", RagDocumentSourceType.LAW, RagTargetCollection.REGULATIONS,
                null, null, null, null, "https://files.example/law.pdf");
        document.startEmbedding();

        assertThatThrownBy(document::restartEmbedding).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void restartEmbedding_임계를넘긴고착임베딩중은재시작허용() {
        // 폴러 유실(JVM 재시작)로 EMBEDDING에 고착된 문서를 관리자가 재임베딩으로 복구할 수 있어야
        // 한다(#1393 P1) — 임계를 0으로 주면 방금 시작한 문서도 stale 취급된다.
        RagDocument document = RagDocument.upload(
                "시설물 안전법", RagDocumentSourceType.LAW, RagTargetCollection.REGULATIONS,
                null, null, null, null, "https://files.example/law.pdf");
        document.startEmbedding();

        document.restartEmbedding(Duration.ZERO);

        assertThat(document.getEmbeddingStatus()).isEqualTo(RagEmbeddingStatus.EMBEDDING);
        assertThat(document.getEmbeddingStartedAt()).isNotNull();
    }

    @Test
    void restartEmbedding_임계이내임베딩중은여전히거부_동시재임베딩레이스방지() {
        RagDocument document = RagDocument.upload(
                "시설물 안전법", RagDocumentSourceType.LAW, RagTargetCollection.REGULATIONS,
                null, null, null, null, "https://files.example/law.pdf");
        document.startEmbedding();

        assertThatThrownBy(() -> document.restartEmbedding(Duration.ofMinutes(5)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(document.getEmbeddingStatus()).isEqualTo(RagEmbeddingStatus.EMBEDDING);
    }

    @Test
    void isEmbeddingStale_임베딩중이아니면고착이아니다() {
        RagDocument document = RagDocument.upload(
                "시설물 안전법", RagDocumentSourceType.LAW, RagTargetCollection.REGULATIONS,
                null, null, null, null, "https://files.example/law.pdf");

        assertThat(document.isEmbeddingStale(Duration.ZERO)).isFalse();
        document.startEmbedding();
        assertThat(document.isEmbeddingStale(Duration.ZERO)).isTrue();
        assertThat(document.isEmbeddingStale(Duration.ofMinutes(5))).isFalse();
    }

    @Test
    void startEmbedding_임베딩시작시각을기록() {
        RagDocument document = RagDocument.upload(
                "시설물 안전법", RagDocumentSourceType.LAW, RagTargetCollection.REGULATIONS,
                null, null, null, null, "https://files.example/law.pdf");

        document.startEmbedding();

        assertThat(document.getEmbeddingStartedAt()).isNotNull();
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
