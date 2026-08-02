package com.hajacheck.core.rag.service;

import com.hajacheck.core.ai.dto.RagEmbeddingStatusResponse;
import com.hajacheck.core.ai.service.AiProxyService;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.global.config.AsyncConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * RAG 문서 임베딩 완료 확인 폴러(#1328) — Cloudflare 504 방지를 위해 FastAPI가 청킹만 동기로 끝내고
 * 실제 임베딩(ingest_document)은 BackgroundTasks로 넘기게 되면서(ai-server 16ffe3bb), Spring이 그
 * 즉시 completeEmbedding()을 호출하면 아직 Chroma에 반영되지 않은 상태를 DONE으로 잘못 마킹하는
 * 거짓 완료가 생긴다.
 *
 * <p>RagDocumentService.embed()가 FastAPI로부터 받은 예상 청크 수(expectedChunkCount)를 들고 이
 * 컴포넌트에 완료 확인을 위임하면, 짧은 간격으로 {@link AiProxyService#checkEmbeddingStatus}를
 * 재시도 폴링해 실제 Chroma 청크 수가 예상치와 일치할 때만 completeEmbedding()을 호출한다. 재시도를
 * 다 쓰고도 일치하지 않으면 failEmbedding()으로 안전한 기본값 처리한다(관리자가 재임베딩으로 복구
 * 가능한 idempotent 설계, RagDocumentService 참고).
 *
 * <p>{@link AsyncConfig#RAG_EMBED_TASK_EXECUTOR} 전용 스레드에서 동작하므로 여기서의
 * {@link Thread#sleep}은 요청 스레드나 nginx 타임아웃과 무관하다 — 사용자 응답은 embed() 호출 시점에
 * 이미 즉시 나간 뒤다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagEmbeddingCompletionPoller {

    /** 폴링 재시도 횟수 — 간격(pollIntervalMs)과 곱해 총 대기 시간을 결정한다(10회 × 2.5초 ≈ 25초). */
    private static final int MAX_ATTEMPTS = 10;
    private static final long DEFAULT_POLL_INTERVAL_MS = 2500L;

    private final AiProxyService aiProxyService;
    private final RagDocumentWriter ragDocumentWriter;

    // 테스트 전용 — 25초짜리 실슬립을 검증할 필요는 없으므로 단위 테스트가 짧게 줄여 넣는다
    // (RagEmbeddingCompletionPollerTest). 프로덕션 경로(@Async 빈 생성)에서는 항상 기본값을 쓴다.
    private long pollIntervalMs = DEFAULT_POLL_INTERVAL_MS;

    void setPollIntervalMsForTest(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    /**
     * @param documentId         RagDocument PK
     * @param collection         FastAPI 소문자 컬렉션 상수(regulations/defect_kb) — RagDocumentService가
     *                            이미 toLowerCase() 매핑을 마친 값을 그대로 전달한다.
     * @param expectedChunkCount FastAPI 청킹 단계에서 즉시 반환된 예상 최종 청크 수
     */
    @Async(AsyncConfig.RAG_EMBED_TASK_EXECUTOR)
    public void pollUntilComplete(Long documentId, String collection, int expectedChunkCount) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (sleepBeforeNextCheck()) {
                log.warn("RAG 임베딩 완료 폴링 중 인터럽트 — documentId={}", documentId);
                ragDocumentWriter.failEmbedding(documentId);
                return;
            }

            try {
                ApiResponse<RagEmbeddingStatusResponse> response =
                        aiProxyService.checkEmbeddingStatus(String.valueOf(documentId), collection);
                if (response.success() && response.data() != null
                        && response.data().chunkCount() == expectedChunkCount) {
                    ragDocumentWriter.completeEmbedding(documentId, expectedChunkCount);
                    return;
                }
            } catch (RuntimeException e) {
                // 조회 자체가 실패한 시도는 재시도로 흡수한다 — 마지막 시도까지 계속 실패하면
                // 루프 종료 후 failEmbedding()으로 귀결된다.
                log.warn("RAG 임베딩 완료 상태 조회 실패(재시도 예정) documentId={} attempt={}/{}",
                        documentId, attempt, MAX_ATTEMPTS, e);
            }
        }

        log.warn("RAG 임베딩 완료 폴링 타임아웃 — documentId={} expectedChunkCount={}",
                documentId, expectedChunkCount);
        ragDocumentWriter.failEmbedding(documentId);
    }

    /**
     * @return true면 인터럽트가 발생해 폴링을 즉시 중단해야 함을 의미한다.
     */
    private boolean sleepBeforeNextCheck() {
        try {
            Thread.sleep(pollIntervalMs);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }
}
