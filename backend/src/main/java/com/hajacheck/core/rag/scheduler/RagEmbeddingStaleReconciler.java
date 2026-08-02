package com.hajacheck.core.rag.scheduler;

import com.hajacheck.core.rag.entity.RagDocument;
import com.hajacheck.core.rag.entity.RagEmbeddingStatus;
import com.hajacheck.core.rag.repository.RagDocumentRepository;
import com.hajacheck.core.rag.service.RagDocumentWriter;
import java.time.Instant;
import java.util.List;
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
 * <p>이 배치가 주기적으로 {@link RagDocument#EMBEDDING_STALE_THRESHOLD}를 넘긴 EMBEDDING 문서를 훑어
 * FAILED로 정리하면, 관리자가 재임베딩으로 복구할 수 있는 상태로 되돌아온다. 임계는 폴러 상한보다
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

    @Scheduled(fixedDelay = RECONCILE_INTERVAL_MS, initialDelay = RECONCILE_INITIAL_DELAY_MS)
    public void reconcileStaleEmbedding() {
        Instant startedBefore = Instant.now().minus(RagDocument.EMBEDDING_STALE_THRESHOLD);
        List<RagDocument> stale =
                ragDocumentRepository.findStaleEmbedding(RagEmbeddingStatus.EMBEDDING, startedBefore);
        if (stale.isEmpty()) {
            return;
        }

        int reconciled = 0;
        for (RagDocument document : stale) {
            try {
                ragDocumentWriter.failEmbedding(document.getId());
                reconciled++;
            } catch (Exception e) {
                // 1건 실패를 격리 — 그사이 폴러가 DONE으로 확정했다면 failEmbedding()이 상태 전이
                // 예외를 던지는데, 그건 정상 완료라 무시해도 되는 경합이다.
                log.warn("RAG 임베딩 고착 리컨사일러 개별 처리 실패 — documentId={} exception={}",
                        document.getId(), e.getClass().getSimpleName());
            }
        }

        log.info("RAG 임베딩 고착 리컨사일러 — 대상 {}건 중 {}건 FAILED 정리", stale.size(), reconciled);
    }
}
