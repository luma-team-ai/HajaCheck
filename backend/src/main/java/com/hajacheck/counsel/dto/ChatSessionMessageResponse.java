package com.hajacheck.counsel.dto;

import com.hajacheck.counsel.entity.ChatMessage;
import com.hajacheck.counsel.entity.ChatSenderType;
import com.hajacheck.core.rag.entity.ChatMessageCitation;
import com.hajacheck.core.rag.entity.RagDocument;
import com.hajacheck.core.rag.entity.RagTargetCollection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 채팅 세션 이력 메시지 응답(#1467/HAJA-647, {@code GET /api/chat-sessions/{id}/messages}).
 *
 * <p>RAG 답변이 인용한 근거({@code chat_message_citations})를 메시지에 함께 실어 새로고침 후에도
 * 출처를 복원할 수 있게 한다. 인용 저장 로직 자체는 후속 이슈 범위라 현재는 비어 있는 것이 정상이다.
 */
public record ChatSessionMessageResponse(
        Long id,
        Long sessionId,
        ChatSenderType sender,
        String content,
        List<Citation> citations,
        LocalDateTime createdAt) {

    /**
     * 답변 근거 1건. Chroma 청크 식별자({@code chunkRef})는 PostgreSQL FK가 아니다.
     *
     * <p>{@code title}/{@code collection}은 {@code citation.getDocument()}(FK 조인, #1698)에서 가져온다 —
     * FE가 제목 대용으로 {@code snippet}(청크 원문 전체)을 쓰던 문제를 없앤다. {@code collection}은
     * 신규 답변 경로({@link com.hajacheck.core.ai.dto.RagChatResponse.SourceCitation#collection()},
     * ai-server {@code vectorstore.py})와 값을 맞추기 위해 {@link RagTargetCollection} enum명을
     * 소문자로 변환한다(예: {@code REGULATIONS} → {@code "regulations"}).
     */
    public record Citation(
            Long documentId, String title, String collection, String chunkRef, String locator, String snippet) {

        public static Citation from(ChatMessageCitation citation) {
            RagDocument document = citation.getDocument();
            return new Citation(
                    citation.getDocumentId(),
                    document.getTitle(),
                    document.getTargetCollection().name().toLowerCase(Locale.ROOT),
                    citation.getChunkRef(),
                    citation.getLocator(),
                    citation.getSnippet());
        }
    }

    public static ChatSessionMessageResponse from(ChatMessage message, List<Citation> citations) {
        return new ChatSessionMessageResponse(
                message.getId(),
                message.getSessionId(),
                message.getSender(),
                message.getContent(),
                citations,
                message.getCreatedAt());
    }
}
