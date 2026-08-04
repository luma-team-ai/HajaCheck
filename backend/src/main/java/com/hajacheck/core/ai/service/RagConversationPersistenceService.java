package com.hajacheck.core.ai.service;

import com.hajacheck.counsel.entity.ChatMessage;
import com.hajacheck.counsel.entity.ChatSenderType;
import com.hajacheck.counsel.repository.ChatMessageRepository;
import com.hajacheck.core.ai.dto.RagChatResponse;
import com.hajacheck.core.rag.entity.ChatMessageCitation;
import com.hajacheck.core.rag.repository.ChatMessageCitationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RAG 챗봇 대화(질문/답변/출처) 저장 전용 서비스(#1493/HAJA-657, PR #1510 P1 픽스).
 *
 * <p>{@link AiProxyService#ragChat} 이 이 저장 로직까지 같은 메서드에서 {@code @Transactional} 로
 * 묶으면, FastAPI LLM 파이프라인 호출(캐시 미스 시 수 초 소요 가능)까지 트랜잭션 범위에 포함돼 그 동안
 * DB 커넥션을 계속 점유한다 — 동시 RAG 요청이 몰리면 커넥션 풀 고갈로 다른 기능까지 영향받을 수 있다.
 * DB 쓰기만 이 별도 빈으로 분리해 {@code @Transactional} 범위를 저장 구간으로 좁힌다(외부 호출은
 * 트랜잭션 밖). Spring self-invocation 문제(같은 클래스 내부 호출은 프록시 트랜잭션이 안 걸림) 때문에
 * {@link AiProxyService} 내부 private 메서드가 아니라 반드시 별도 빈이어야 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagConversationPersistenceService {

    private final ChatMessageRepository chatMessageRepository;
    private final ChatMessageCitationRepository chatMessageCitationRepository;

    /** 사용자 질문 + 봇 답변을 저장하고, 답변의 출처를 봇 메시지에 인용으로 매핑해 저장한다. */
    @Transactional
    public void saveConversation(Long sessionId, String query, RagChatResponse data) {
        chatMessageRepository.save(ChatMessage.createText(sessionId, ChatSenderType.USER, query));
        ChatMessage botMessage = chatMessageRepository.save(
                ChatMessage.createText(sessionId, ChatSenderType.BOT, data.answer()));

        if (data.sources() == null) {
            return;
        }
        for (RagChatResponse.SourceCitation source : data.sources()) {
            Long documentId = parseDocumentId(source.docId());
            if (documentId == null) {
                continue;
            }
            chatMessageCitationRepository.save(ChatMessageCitation.create(
                    botMessage.getId(), documentId, source.chunkRef(), source.locator(), source.snippet()));
        }
    }

    private Long parseDocumentId(String docId) {
        try {
            return Long.parseLong(docId);
        } catch (NumberFormatException e) {
            log.warn("RAG 출처 doc_id 파싱 실패 — citation 저장 생략: {}", docId);
            return null;
        }
    }
}
