package com.hajacheck.core.rag.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.core.ai.dto.RagEmbeddingStatusResponse;
import com.hajacheck.core.ai.service.AiProxyService;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * RagEmbeddingCompletionPoller 단위 테스트(#1328). {@code @Async} 자체는 스프링 컨테이너 없이는
 * 검증할 수 없으므로(호출 스레드 분리는 통합 관심사), 여기서는 {@code pollUntilComplete()} 메서드를
 * 직접 호출해 폴링 로직(성공/재시도 후 실패)만 검증한다 — 프로덕션 간격(2.5초 × 최대 10회 ≈ 25초)을
 * 그대로 쓰면 재시도 상한 케이스마다 테스트가 25초씩 걸려 느려지므로,
 * {@code setPollIntervalMsForTest()}로 간격만 테스트 전용으로 줄인다(재시도 횟수 로직 자체는 그대로).
 */
@ExtendWith(MockitoExtension.class)
class RagEmbeddingCompletionPollerTest {

    @Mock
    private AiProxyService aiProxyService;
    @Mock
    private RagDocumentWriter ragDocumentWriter;

    @InjectMocks
    private RagEmbeddingCompletionPoller poller;

    @BeforeEach
    void setUp() {
        poller.setPollIntervalMsForTest(1L);
    }

    @Test
    void pollUntilComplete_첫시도에실제청크수가예상치와일치하면_즉시완료처리하고재시도하지않는다() {
        when(aiProxyService.checkEmbeddingStatus("1", "regulations"))
                .thenReturn(ApiResponse.ok(new RagEmbeddingStatusResponse(5, "batch-1")));

        poller.pollUntilComplete(1L, "regulations", 5, "batch-1");

        verify(aiProxyService, times(1)).checkEmbeddingStatus("1", "regulations");
        verify(ragDocumentWriter).completeEmbedding(1L, 5);
        verify(ragDocumentWriter, never()).failEmbedding(any());
    }

    @Test
    void pollUntilComplete_재시도끝에도청크수가일치하지않으면_실패확정하지않고EMBEDDING을유지한다() {
        // 상한(약 25초)을 넘겨도 배경 임베딩이 계속 진행 중일 수 있어(대용량 문서), 여기서 FAILED로
        // 확정하면 실제로는 성공한 문서가 실패로 남는다(#1393 리뷰 P2). 최종 판정은 임계까지 기다리는
        // RagEmbeddingStaleReconciler에 넘기고, 폴러는 문서 상태를 건드리지 않고 종료한다.
        when(aiProxyService.checkEmbeddingStatus("2", "defect_kb"))
                .thenReturn(ApiResponse.ok(new RagEmbeddingStatusResponse(0, null)));

        poller.pollUntilComplete(2L, "defect_kb", 5, "batch-1");

        verify(ragDocumentWriter, never()).completeEmbedding(any(), org.mockito.ArgumentMatchers.anyInt());
        verify(ragDocumentWriter, never()).failEmbedding(any());
    }

    @Test
    void pollUntilComplete_조회가매번예외를던져도_재시도를흡수하고상태를건드리지않는다() {
        when(aiProxyService.checkEmbeddingStatus(eq("3"), eq("regulations")))
                .thenThrow(new BusinessException(ErrorCode.AI_SERVER_UNREACHABLE));

        poller.pollUntilComplete(3L, "regulations", 5, "batch-1");

        verify(ragDocumentWriter, never()).completeEmbedding(any(), org.mockito.ArgumentMatchers.anyInt());
        verify(ragDocumentWriter, never()).failEmbedding(any());
    }

    @Test
    void pollUntilComplete_청크수는같아도배치식별자가옛값이면_완료로확정하지않는다() {
        // 재임베딩은 add_texts(upsert)라 옛 청크가 그대로 남아 있어, 청크 수가 같으면 배경 임베딩이
        // 끝나기 전에도 기대치와 일치해버린다(#1393 리뷰 P2) — 배치 식별자로 이번 배치인지 가린다.
        when(aiProxyService.checkEmbeddingStatus("5", "regulations"))
                .thenReturn(ApiResponse.ok(new RagEmbeddingStatusResponse(5, "old-batch")));

        poller.pollUntilComplete(5L, "regulations", 5, "new-batch");

        verify(ragDocumentWriter, never()).completeEmbedding(any(), org.mockito.ArgumentMatchers.anyInt());
        verify(ragDocumentWriter, never()).failEmbedding(any());
    }

    @Test
    void pollUntilComplete_옛배치로시작해_새배치가확인되면완료처리한다() {
        when(aiProxyService.checkEmbeddingStatus("6", "regulations"))
                .thenReturn(ApiResponse.ok(new RagEmbeddingStatusResponse(5, "old-batch")))
                // 재임베딩 진행 중 — 옛/새 배치 청크가 섞여 있으면 ai-server가 null을 돌려준다.
                .thenReturn(ApiResponse.ok(new RagEmbeddingStatusResponse(5, null)))
                .thenReturn(ApiResponse.ok(new RagEmbeddingStatusResponse(5, "new-batch")));

        poller.pollUntilComplete(6L, "regulations", 5, "new-batch");

        verify(aiProxyService, times(3)).checkEmbeddingStatus("6", "regulations");
        verify(ragDocumentWriter).completeEmbedding(6L, 5);
    }

    @Test
    void pollUntilComplete_예상청크수가0이면_배치식별자없이도즉시완료처리한다() {
        // 빈/추출불가 텍스트는 청크가 0개라 ai-server가 add_texts를 아예 호출하지 않아 어떤 청크에도
        // embed_batch_id가 실리지 않는다(#1393 리뷰 2차 P2) — 배치 대조를 요구하면 영구 FAILED가 된다.
        when(aiProxyService.checkEmbeddingStatus("7", "defect_kb"))
                .thenReturn(ApiResponse.ok(new RagEmbeddingStatusResponse(0, null)));

        poller.pollUntilComplete(7L, "defect_kb", 0, "batch-1");

        verify(aiProxyService, times(1)).checkEmbeddingStatus("7", "defect_kb");
        verify(ragDocumentWriter, times(1)).completeEmbedding(7L, 0);
        verify(ragDocumentWriter, never()).failEmbedding(any());
    }

    @Test
    void pollUntilComplete_일부시도만실패하다_이후시도에서일치하면완료처리한다() {
        when(aiProxyService.checkEmbeddingStatus("4", "regulations"))
                .thenThrow(new BusinessException(ErrorCode.AI_SERVER_TIMEOUT))
                .thenReturn(ApiResponse.ok(new RagEmbeddingStatusResponse(2, "batch-1")))
                .thenReturn(ApiResponse.ok(new RagEmbeddingStatusResponse(5, "batch-1")));

        poller.pollUntilComplete(4L, "regulations", 5, "batch-1");

        verify(aiProxyService, times(3)).checkEmbeddingStatus("4", "regulations");
        verify(ragDocumentWriter).completeEmbedding(4L, 5);
        verify(ragDocumentWriter, never()).failEmbedding(any());
    }
}
