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
                .thenReturn(ApiResponse.ok(new RagEmbeddingStatusResponse(5)));

        poller.pollUntilComplete(1L, "regulations", 5);

        verify(aiProxyService, times(1)).checkEmbeddingStatus("1", "regulations");
        verify(ragDocumentWriter).completeEmbedding(1L, 5);
        verify(ragDocumentWriter, never()).failEmbedding(any());
    }

    @Test
    void pollUntilComplete_재시도끝에도청크수가일치하지않으면_failEmbedding으로안전한기본값처리한다() {
        // 실제 청크 수가 계속 기대치보다 적게 조회되는 경우(임베딩이 끝나지 않았거나 실패한 경우)
        // 최대 재시도 횟수를 다 쓰고도 일치하지 않으면 완료로 확정하지 않고 실패 처리한다.
        when(aiProxyService.checkEmbeddingStatus("2", "defect_kb"))
                .thenReturn(ApiResponse.ok(new RagEmbeddingStatusResponse(0)));

        poller.pollUntilComplete(2L, "defect_kb", 5);

        verify(ragDocumentWriter, never()).completeEmbedding(any(), org.mockito.ArgumentMatchers.anyInt());
        verify(ragDocumentWriter).failEmbedding(2L);
    }

    @Test
    void pollUntilComplete_조회가매번예외를던져도_재시도를흡수하고최종적으로failEmbedding한다() {
        when(aiProxyService.checkEmbeddingStatus(eq("3"), eq("regulations")))
                .thenThrow(new BusinessException(ErrorCode.AI_SERVER_UNREACHABLE));

        poller.pollUntilComplete(3L, "regulations", 5);

        verify(ragDocumentWriter, never()).completeEmbedding(any(), org.mockito.ArgumentMatchers.anyInt());
        verify(ragDocumentWriter).failEmbedding(3L);
    }

    @Test
    void pollUntilComplete_일부시도만실패하다_이후시도에서일치하면완료처리한다() {
        when(aiProxyService.checkEmbeddingStatus("4", "regulations"))
                .thenThrow(new BusinessException(ErrorCode.AI_SERVER_TIMEOUT))
                .thenReturn(ApiResponse.ok(new RagEmbeddingStatusResponse(2)))
                .thenReturn(ApiResponse.ok(new RagEmbeddingStatusResponse(5)));

        poller.pollUntilComplete(4L, "regulations", 5);

        verify(aiProxyService, times(3)).checkEmbeddingStatus("4", "regulations");
        verify(ragDocumentWriter).completeEmbedding(4L, 5);
        verify(ragDocumentWriter, never()).failEmbedding(any());
    }
}
