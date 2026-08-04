package com.hajacheck.core.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hajacheck.core.ai.dto.RagChatResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 대화 저장 오케스트레이터 단위테스트 — 메시지/출처 두 writer 로의 위임과, 출처 저장 실패를 여기서
 * 흡수해 대화 이력을 지키는 계약(#1593)을 고정한다. 실제 트랜잭션 경계 검증은
 * {@link RagConversationBestEffortIntegrationTest} 참고.
 */
class RagConversationPersistenceServiceTest {

    private static final Long SESSION_ID = 100L;
    private static final Long BOT_MESSAGE_ID = 200L;
    private static final String QUERY = "균열 보수 기준은 무엇인가요?";
    private static final String ANSWER = "균열 폭이 0.3mm 이상인 경우 보수해야 합니다.";

    private RagChatMessageWriter ragChatMessageWriter;
    private RagCitationWriter ragCitationWriter;
    private RagConversationPersistenceService service;

    @BeforeEach
    void setUp() {
        ragChatMessageWriter = mock(RagChatMessageWriter.class);
        ragCitationWriter = mock(RagCitationWriter.class);
        when(ragChatMessageWriter.saveMessages(anyLong(), anyString(), anyString()))
                .thenReturn(BOT_MESSAGE_ID);
        service = new RagConversationPersistenceService(ragChatMessageWriter, ragCitationWriter);
    }

    @Test
    void saveConversation_메시지저장후_봇메시지id로출처저장을위임() {
        List<RagChatResponse.SourceCitation> sources = List.of(
                new RagChatResponse.SourceCitation("12", "균열관리기준", "regulations", "3페이지", "0.3mm 이상 보수", "chunk-abc-123"),
                new RagChatResponse.SourceCitation("34", "하자판정기준", "regulations", "5페이지", "허용 폭 기준", "chunk-def-456")
        );

        service.saveConversation(SESSION_ID, QUERY, new RagChatResponse(ANSWER, sources));

        verify(ragChatMessageWriter).saveMessages(SESSION_ID, QUERY, ANSWER);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RagChatResponse.SourceCitation>> captor = ArgumentCaptor.forClass(List.class);
        verify(ragCitationWriter).saveCitations(eq(BOT_MESSAGE_ID), captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    @Test
    void saveConversation_sources가null이면출처저장호출없이메시지만저장() {
        service.saveConversation(SESSION_ID, QUERY, new RagChatResponse(ANSWER, null));

        verify(ragChatMessageWriter).saveMessages(SESSION_ID, QUERY, ANSWER);
        verifyNoInteractions(ragCitationWriter);
    }

    @Test
    void saveConversation_sources가빈리스트여도출처저장호출없음() {
        service.saveConversation(SESSION_ID, QUERY, new RagChatResponse(ANSWER, List.of()));

        verify(ragChatMessageWriter).saveMessages(SESSION_ID, QUERY, ANSWER);
        verifyNoInteractions(ragCitationWriter);
    }

    @Test
    @DisplayName("출처 저장이 실패해도 예외를 삼켜 대화 이력을 지키고, 원인은 docIds 와 함께 ERROR 로 남긴다")
    void saveConversation_출처저장실패_예외전파없이에러로그만남긴다() {
        doThrow(new DataIntegrityViolationException("violates foreign key constraint"))
                .when(ragCitationWriter).saveCitations(eq(BOT_MESSAGE_ID), any());
        RagChatResponse data = new RagChatResponse(ANSWER, List.of(
                new RagChatResponse.SourceCitation("12", "t", "regulations", "3페이지", "s", "chunk-1"),
                new RagChatResponse.SourceCitation("34", "t", "regulations", "5페이지", "s", "chunk-2")));

        Logger logger = (Logger) LoggerFactory.getLogger(RagConversationPersistenceService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            // 메시지 트랜잭션은 이미 커밋된 뒤다 — 여기서 던지면 "이력은 남았는데 500" 이 된다.
            service.saveConversation(SESSION_ID, QUERY, data);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("RAG 출처(citation) 저장 실패");
            // 어떤 문서가 문제였는지가 없으면 FK 위반 추적이 불가능하다.
            assertThat(event.getFormattedMessage()).contains("[12,34]");
            assertThat(event.getThrowableProxy()).isNotNull();
        });
    }

    @Test
    @DisplayName("메시지 저장 실패는 삼키지 않고 호출부(AiProxyService)로 그대로 던진다")
    void saveConversation_메시지저장실패_예외를그대로전파한다() {
        // 이 클래스에는 @Transactional 이 없어 여기서 잡을 이유가 없고, best-effort 흡수 지점은
        // 호출부 한 곳이어야 한다(흩어지면 어느 실패가 어디서 먹히는지 추적 불가).
        doThrow(new DataIntegrityViolationException("chat_messages 저장 실패"))
                .when(ragChatMessageWriter).saveMessages(anyLong(), anyString(), anyString());

        assertThatThrownBy(() -> service.saveConversation(
                SESSION_ID, QUERY, new RagChatResponse(ANSWER, List.of())))
                .isInstanceOf(DataIntegrityViolationException.class);
        verifyNoInteractions(ragCitationWriter);
    }
}
