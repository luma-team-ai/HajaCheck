package com.hajacheck.core.rag.entity;

import com.hajacheck.global.exception.DomainStateTransitionException;
import com.hajacheck.global.exception.DomainValidationException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
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

    /**
     * EMBEDDING 고착 판정 임계(#1393) — 이보다 오래 EMBEDDING이면 배경 임베딩이 끝나지 않았거나
     * 폴러가 유실된 것으로 본다. 폴러 상한(약 25초)보다 충분히 크게 잡아, 폴러가 포기한 뒤에도
     * 대용량 문서의 배경 임베딩이 끝날 여유를 남긴다.
     */
    public static final Duration EMBEDDING_STALE_THRESHOLD = Duration.ofMinutes(5);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

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

    /**
     * 임베딩 시작 시각(#1393) — EMBEDDING 고착 판정의 유일한 근거다. JVM 재시작으로
     * {@link com.hajacheck.core.rag.service.RagEmbeddingCompletionPoller}(인메모리 @Async)가 통째로
     * 유실되면 문서가 EMBEDDING에 영구 고착되는데, 이 컬럼이 있어야
     * {@link com.hajacheck.core.rag.scheduler.RagEmbeddingStaleReconciler}가 "언제부터 EMBEDDING이었나"를
     * 알고 stale을 정리할 수 있다.
     */
    @Column(name = "embedding_started_at")
    private Instant embeddingStartedAt;

    /**
     * 이번 임베딩 요청이 기대하는 청크 수·배치 식별자(#1393 리뷰 2차 P2) — 폴러가 25초 상한에
     * 도달해 EMBEDDING을 유지한 채 종료하면, {@link com.hajacheck.core.rag.scheduler.RagEmbeddingStaleReconciler}가
     * 이 두 값으로 {@code AiProxyService.checkEmbeddingStatus()}를 재조회해 실제로 완료됐는지 채점한다.
     * embedding_started_at은 "얼마나 오래 걸렸나"만 알려줄 뿐 "정답이 뭔가"는 알려주지 못하므로 별도로 둔다.
     */
    @Column(name = "expected_chunk_count")
    private Integer expectedChunkCount;

    @Column(name = "embed_batch_id", length = 64)
    private String embedBatchId;

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
        this.embeddingStartedAt = Instant.now();
    }

    /**
     * EMBEDDING 상태가 임계를 넘도록 지속됐는지 — 폴러 유실(JVM 재시작)이나 배경 임베딩 실패로 고착된
     * 문서를 가려낸다. 시작 시각이 없는 레거시 행(마이그레이션 이전에 EMBEDDING으로 남은 문서)도
     * 복구 대상이므로 stale로 본다.
     */
    public boolean isEmbeddingStale(Duration staleThreshold) {
        if (this.embeddingStatus != RagEmbeddingStatus.EMBEDDING) {
            return false;
        }
        return this.embeddingStartedAt == null
                || this.embeddingStartedAt.isBefore(Instant.now().minus(staleThreshold));
    }

    /**
     * AI 서버가 임베딩 요청을 접수한 직후(청킹 완료, 배경 임베딩 시작 시점) 이번 요청의 기대값을
     * 기록한다(#1393 리뷰 2차 P2) — startEmbedding()/restartEmbedding() 시점에는 아직 AI 서버 응답
     * 전이라 이 값을 알 수 없어 별도 메서드로 분리한다. EMBEDDING 상태에서만 의미가 있다.
     */
    public void recordEmbedRequest(int expectedChunkCount, String embedBatchId) {
        requireEmbeddingStatus("recordEmbedRequest", RagEmbeddingStatus.EMBEDDING);
        this.expectedChunkCount = expectedChunkCount;
        this.embedBatchId = embedBatchId;
    }

    public void completeEmbedding(int chunkCount) {
        requireEmbeddingStatus("completeEmbedding", RagEmbeddingStatus.EMBEDDING);
        if (chunkCount < 0) {
            throw new DomainValidationException("completeEmbedding 불가: 청크 수는 0 이상이어야 한다");
        }
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
        throw new DomainStateTransitionException(
                "%s 불가: 현재 임베딩 상태=%s, 허용 상태=%s"
                        .formatted(action, this.embeddingStatus, Arrays.toString(allowed)));
    }

    /**
     * 재임베딩 시작(관리자 명시적 액션, #22/HAJA-35) — PENDING/DONE/FAILED 어떤 상태에서도 곧바로
     * EMBEDDING으로 전이하는 단일 원자 전이다. 이전에는 resetForReEmbed()(→PENDING)와
     * startEmbedding()(→EMBEDDING)을 별도 트랜잭션 2번으로 나눠 호출해, 그 사이 짧은 순간 방금까지
     * DONE이던 문서가 "아직 처리 안 됨"을 뜻하는 PENDING으로 잘못 조회되는 창이 있었다(code-review
     * P2). 단일 전이로 합쳐 그 창을 없앤다. EMBEDDING 중에는 거부해 동시 재임베딩 레이스를 막는다
     * (재임베딩은 "명시적 배치 잡"으로 한정 — 진행 중인 잡 위에 또 트리거되지 않게).
     *
     * <p>다만 EMBEDDING 전면 거부는 폴러 유실(JVM 재시작) 시 관리자가 재임베딩으로도 복구할 수 없는
     * 영구 고착을 만든다(#1393 리뷰 P1). 그래서 {@link #EMBEDDING_STALE_THRESHOLD}를 넘긴 stale
     * EMBEDDING만 재시작을 허용하고, 임계 이내(진행 중일 가능성이 높은)는 여전히 거부한다.
     * {@link com.hajacheck.core.rag.scheduler.RagEmbeddingStaleReconciler}가 먼저 FAILED로 정리해둔
     * 경우에도 정상 FAILED 경로로 동작하므로 두 메커니즘이 겹쳐도 안전하다.
     */
    public void restartEmbedding() {
        restartEmbedding(EMBEDDING_STALE_THRESHOLD);
    }

    /**
     * @param staleThreshold EMBEDDING 재시작 허용 임계 — 이보다 오래 EMBEDDING이었던 문서만 재시작을
     *                       허용한다(테스트가 임계를 직접 주입할 수 있게 분리).
     */
    public void restartEmbedding(Duration staleThreshold) {
        if (this.embeddingStatus == RagEmbeddingStatus.EMBEDDING && !isEmbeddingStale(staleThreshold)) {
            // 임계 이내 EMBEDDING = 배경 임베딩이 아직 진행 중일 가능성이 높다 → 동시 재임베딩 레이스
            // 방지를 위해 계속 거부한다.
            throw new DomainStateTransitionException(
                    "restartEmbedding 불가: 임베딩이 진행 중이다(시작 시각=%s, 임계=%s)"
                            .formatted(this.embeddingStartedAt, staleThreshold));
        }
        requireEmbeddingStatus("restartEmbedding", RagEmbeddingStatus.PENDING, RagEmbeddingStatus.DONE,
                RagEmbeddingStatus.FAILED, RagEmbeddingStatus.EMBEDDING);
        this.embeddingStatus = RagEmbeddingStatus.EMBEDDING;
        this.embeddingStartedAt = Instant.now();
    }

    public void verify() {
        if (this.verificationStatus == RagDocumentVerificationStatus.VERIFIED) {
            return;
        }
        this.verificationStatus = RagDocumentVerificationStatus.VERIFIED;
    }
}
