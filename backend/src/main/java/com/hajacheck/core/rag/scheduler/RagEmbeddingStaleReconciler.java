package com.hajacheck.core.rag.scheduler;

import com.hajacheck.core.ai.dto.RagEmbeddingStatusResponse;
import com.hajacheck.core.ai.service.AiProxyService;
import com.hajacheck.core.rag.entity.RagDocument;
import com.hajacheck.core.rag.entity.RagEmbeddingStatus;
import com.hajacheck.core.rag.repository.RagDocumentRepository;
import com.hajacheck.core.rag.service.RagDocumentWriter;
import com.hajacheck.core.rag.service.RagEmbeddingCompletionCheck;
import com.hajacheck.global.common.ApiResponse;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * EMBEDDING 고착 문서 리컨사일러(#1393, PR머신 리뷰 P1) — StuckAnalysisReaper와 동일한 패턴.
 *
 * <p>임베딩 완료 확정은 {@link com.hajacheck.core.rag.service.RagEmbeddingCompletionPoller}가 담당하는데,
 * 이 폴러는 인메모리 {@code @Async} 작업이라 영속 상태가 없다. markEmbeddingStarted()가 EMBEDDING을
 * 이미 커밋한 뒤 폴링 창(약 25초) 안에 JVM이 재시작되면(배포·크래시·롤링 재시작) 폴러가 통째로 유실되고
 * 문서는 EMBEDDING으로 영구 고착된다. 폴러가 상한까지 확인하지 못한 경우도 마찬가지로 EMBEDDING이
 * 남는다(대용량 문서의 배경 임베딩이 25초 안에 안 끝날 수 있어, 폴러는 상한 도달 시 곧바로 실패로
 * 확정하지 않고 최종 판정을 이 리컨사일러에 넘긴다).
 *
 * <p>이 배치가 주기적으로 {@link RagDocument#EMBEDDING_STALE_THRESHOLD}를 넘긴 EMBEDDING 문서를 훑는다.
 * 다만 무조건 FAILED로 정리하면 대용량 문서(배경 임베딩이 25초는 넘지만 결국 성공하는 경우 — 이 PR이
 * 원래 겨냥한 대상)까지 거짓 FAILED로 확정하는 회귀가 생긴다(PR머신 리뷰 2차 P2). 그래서 FAILED로
 * 정리하기 전에 문서에 영속된 기대값(expectedChunkCount/embedBatchId, RagDocument.recordEmbedRequest())
 * 으로 AiProxyService.checkEmbeddingStatus()를 재조회해 실제로 완료됐는지 재확인하고, 완료됐으면
 * DONE으로 확정한다(폴러와 동일한 RagEmbeddingCompletionCheck 판정 로직을 공유). 임계는 폴러 상한보다
 * 충분히 커서 배경 임베딩이 뒤늦게 완료될 여유를 남긴다.
 *
 * <p>⚠️ 단일 인스턴스 실행 전제(StuckAnalysisReaper와 동일). 스케일아웃해도 failEmbedding()은 "여전히
 * EMBEDDING일 때만" 전이하는 멱등 연산이라 중복 실행이 데이터를 손상시키지 않는다. 문서별 실패를
 * 격리해 한 건 실패가 배치 전체를 멈추지 않게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagEmbeddingStaleReconciler {

    /** 임계(5분)와 같은 주기로 돌아 고착 문서가 최대 임계의 2배 이상 잔류하지 않게 한다. */
    private static final long RECONCILE_INTERVAL_MS = 300_000L;
    private static final long RECONCILE_INITIAL_DELAY_MS = 60_000L;

    private final RagDocumentRepository ragDocumentRepository;
    private final RagDocumentWriter ragDocumentWriter;
    private final AiProxyService aiProxyService;

    @Scheduled(fixedDelay = RECONCILE_INTERVAL_MS, initialDelay = RECONCILE_INITIAL_DELAY_MS)
    public void reconcileStaleEmbedding() {
        Instant startedBefore = Instant.now().minus(RagDocument.EMBEDDING_STALE_THRESHOLD);
        List<RagDocument> stale =
                ragDocumentRepository.findStaleEmbedding(RagEmbeddingStatus.EMBEDDING, startedBefore);
        if (stale.isEmpty()) {
            return;
        }

        int completed = 0;
        int failed = 0;
        for (RagDocument document : stale) {
            try {
                if (isActuallyComplete(document)) {
                    ragDocumentWriter.completeEmbedding(document.getId(), document.getExpectedChunkCount());
                    completed++;
                } else {
                    ragDocumentWriter.failEmbedding(document.getId());
                    failed++;
                }
            } catch (Exception e) {
                // 1건 실패를 격리 — 그사이 폴러가 먼저 확정했다면 completeEmbedding()/failEmbedding()이
                // 상태 전이 예외를 던지는데, 그건 정상 완료라 무시해도 되는 경합이다.
                log.warn("RAG 임베딩 고착 리컨사일러 개별 처리 실패 — documentId={} exception={}",
                        document.getId(), e.getClass().getSimpleName());
            }
        }

        log.info("RAG 임베딩 고착 리컨사일러 — 대상 {}건 중 {}건 DONE 확정, {}건 FAILED 정리",
                stale.size(), completed, failed);
    }

    /**
     * 폴러 상한(25초) 이후 방치된 문서가 실제로는 Chroma에 정상 적재됐는지 재확인한다(#1393 리뷰 2차
     * P2) — 재확인 없이 무조건 FAILED로 정리하면 대용량 문서(이 PR이 원래 겨냥한 대상)가 성공했음에도
     * 거짓 FAILED가 된다. expectedChunkCount/embedBatchId가 없는 레거시 행(V39 이전 EMBEDDING 잔재)은
     * 재확인할 기대값 자체가 없으므로 재확인을 건너뛰고 기존처럼 FAILED로 정리한다.
     */
    private boolean isActuallyComplete(RagDocument document) {
        if (document.getExpectedChunkCount() == null) {
            return false;
        }
        String collection = document.getTargetCollection().name().toLowerCase(Locale.ROOT);
        ApiResponse<RagEmbeddingStatusResponse> response =
                aiProxyService.checkEmbeddingStatus(String.valueOf(document.getId()), collection);
        if (!response.success()) {
            return false;
        }
        return RagEmbeddingCompletionCheck.isComplete(
                response.data(), document.getExpectedChunkCount(), document.getEmbedBatchId());
    }
}
