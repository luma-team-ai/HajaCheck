package com.hajacheck.core.rag.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hajacheck.core.ai.dto.RagEmbeddingStatusResponse;
import com.hajacheck.core.ai.service.AiProxyService;
import com.hajacheck.core.rag.entity.RagDocument;
import com.hajacheck.core.rag.entity.RagDocumentSourceType;
import com.hajacheck.core.rag.entity.RagEmbeddingStatus;
import com.hajacheck.core.rag.entity.RagTargetCollection;
import com.hajacheck.core.rag.repository.RagDocumentRepository;
import com.hajacheck.core.rag.service.RagDocumentWriter;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.DomainStateTransitionException;
import com.hajacheck.global.exception.ErrorCode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * RagEmbeddingStaleReconciler 단위 테스트(#1393 P1) — {@code @Scheduled} 트리거 자체는 통합 관심사라
 * 여기서는 reconcileStaleEmbedding()을 직접 호출해 "stale 문서를 failEmbedding으로 전이시키는가"만
 * 검증한다. 실제 재확인 작업은 전용 executor로 offload되므로(PR머신 리뷰 P1), mock TaskExecutor가
 * execute()에 넘어온 Runnable을 즉시 같은 스레드에서 실행하도록 스텁해 테스트를 동기로 만든다.
 */
@ExtendWith(MockitoExtension.class)
class RagEmbeddingStaleReconcilerTest {

    @Mock
    private RagDocumentRepository ragDocumentRepository;
    @Mock
    private RagDocumentWriter ragDocumentWriter;
    @Mock
    private AiProxyService aiProxyService;
    @Mock
    private TaskExecutor ragEmbedTaskExecutor;

    @InjectMocks
    private RagEmbeddingStaleReconciler reconciler;

    @BeforeEach
    void stubExecutorToRunSynchronously() {
        lenient().doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(ragEmbedTaskExecutor).execute(any());
    }

    private RagDocument staleDocument(long id) {
        RagDocument document = RagDocument.upload(
                "시설물 안전법", RagDocumentSourceType.LAW, RagTargetCollection.REGULATIONS,
                null, null, null, null, "https://files.example/law.pdf");
        document.startEmbedding();
        ReflectionTestUtils.setField(document, "id", id);
        return document;
    }

    @Test
    void reconcileStaleEmbedding_고착문서를failEmbedding으로정리한다() {
        when(ragDocumentRepository.findStaleEmbedding(eq(RagEmbeddingStatus.EMBEDDING), any(Instant.class)))
                .thenReturn(List.of(staleDocument(1L), staleDocument(2L)));

        reconciler.reconcileStaleEmbedding();

        verify(ragDocumentWriter).failEmbedding(1L);
        verify(ragDocumentWriter).failEmbedding(2L);
    }

    @Test
    void reconcileStaleEmbedding_대상이없으면아무것도하지않는다() {
        when(ragDocumentRepository.findStaleEmbedding(eq(RagEmbeddingStatus.EMBEDDING), any(Instant.class)))
                .thenReturn(List.of());

        reconciler.reconcileStaleEmbedding();

        verifyNoInteractions(ragDocumentWriter);
    }

    @Test
    void reconcileStaleEmbedding_한건실패를격리하고나머지를계속처리한다() {
        // 그사이 폴러가 DONE으로 확정한 문서는 failEmbedding()이 상태 전이 예외를 던지는데,
        // 배치 전체가 멈추면 안 된다.
        when(ragDocumentRepository.findStaleEmbedding(eq(RagEmbeddingStatus.EMBEDDING), any(Instant.class)))
                .thenReturn(List.of(staleDocument(1L), staleDocument(2L)));
        doThrow(new DomainStateTransitionException("failEmbedding 불가"))
                .when(ragDocumentWriter).failEmbedding(1L);

        reconciler.reconcileStaleEmbedding();

        verify(ragDocumentWriter).failEmbedding(2L);
    }

    @Test
    void reconcileStaleEmbedding_상태조회예외시확인불가로보고FAILED하지않는다() {
        // 배포 스큐(#1393 사람 검수 P2) — Spring이 먼저 배포돼 embedding-status가 아직 없는
        // ai-server를 호출하면 AiProxyService가 BusinessException(AI_INVALID_RESPONSE)을 던진다.
        // 이걸 "확정적으로 미완료"로 보고 FAILED 처리하면 ai-server 승격 전까지 문서가 영구히
        // FAILED로 오표기된다 — 확인 불가로 보고 EMBEDDING을 유지해야 한다.
        RagDocument document = staleDocument(1L);
        document.recordEmbedRequest(5, "batch-1");
        when(ragDocumentRepository.findStaleEmbedding(eq(RagEmbeddingStatus.EMBEDDING), any(Instant.class)))
                .thenReturn(List.of(document));
        when(aiProxyService.checkEmbeddingStatus("1", "regulations"))
                .thenThrow(new BusinessException(ErrorCode.AI_INVALID_RESPONSE));

        reconciler.reconcileStaleEmbedding();

        verify(ragDocumentWriter, never()).failEmbedding(any());
        verify(ragDocumentWriter, never()).completeEmbedding(any(), any(Integer.class));
    }

    @Test
    void reconcileStaleEmbedding_구버전ai서버가배치식별자를안주면확인불가로보고FAILED하지않는다() {
        // 배포 스큐 — ai-server가 먼저 구버전인 채로 embed_batch_id 필드 없이 응답하면(또는 재임베딩
        // 배치가 아직 혼재 중이면) embedBatchId가 항상 null이다. 청크 수가 0이 아닌데 배치 식별자가
        // 없는 경우를 "확정적으로 미완료"가 아니라 "확인 불가"로 봐서 FAILED를 보류한다.
        RagDocument document = staleDocument(2L);
        document.recordEmbedRequest(5, "batch-1");
        when(ragDocumentRepository.findStaleEmbedding(eq(RagEmbeddingStatus.EMBEDDING), any(Instant.class)))
                .thenReturn(List.of(document));
        when(aiProxyService.checkEmbeddingStatus("2", "regulations"))
                .thenReturn(ApiResponse.ok(new RagEmbeddingStatusResponse(5, null)));

        reconciler.reconcileStaleEmbedding();

        verify(ragDocumentWriter, never()).failEmbedding(any());
        verify(ragDocumentWriter, never()).completeEmbedding(any(), any(Integer.class));
    }

    @Test
    void reconcileStaleEmbedding_실제완료확인되면DONE으로확정한다() {
        RagDocument document = staleDocument(3L);
        document.recordEmbedRequest(5, "batch-1");
        when(ragDocumentRepository.findStaleEmbedding(eq(RagEmbeddingStatus.EMBEDDING), any(Instant.class)))
                .thenReturn(List.of(document));
        when(aiProxyService.checkEmbeddingStatus("3", "regulations"))
                .thenReturn(ApiResponse.ok(new RagEmbeddingStatusResponse(5, "batch-1")));

        reconciler.reconcileStaleEmbedding();

        verify(ragDocumentWriter).completeEmbedding(3L, 5);
        verify(ragDocumentWriter, never()).failEmbedding(any());
    }

    @Test
    void reconcileStaleEmbedding_임계이전시작분만조회한다() {
        when(ragDocumentRepository.findStaleEmbedding(eq(RagEmbeddingStatus.EMBEDDING), any(Instant.class)))
                .thenReturn(List.of());
        Instant before = Instant.now().minus(RagDocument.EMBEDDING_STALE_THRESHOLD);

        reconciler.reconcileStaleEmbedding();

        verify(ragDocumentRepository).findStaleEmbedding(
                eq(RagEmbeddingStatus.EMBEDDING),
                org.mockito.ArgumentMatchers.argThat(cutoff ->
                        !cutoff.isBefore(before) && !cutoff.isAfter(Instant.now())));
        verify(ragDocumentWriter, never()).failEmbedding(any());
    }
}
