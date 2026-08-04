package com.hajacheck.core.ai.service;

import com.hajacheck.counsel.entity.ChatMessage;
import com.hajacheck.counsel.entity.ChatSenderType;
import com.hajacheck.counsel.repository.ChatMessageRepository;
import com.hajacheck.core.ai.dto.RagChatResponse;
import com.hajacheck.core.rag.entity.ChatMessageCitation;
import com.hajacheck.core.rag.repository.ChatMessageCitationRepository;
import java.util.HashSet;
import java.util.Set;
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

    /**
     * 사용자 질문 + 봇 답변을 저장하고, 답변의 출처를 봇 메시지에 인용으로 매핑해 저장한다.
     *
     * <p>이 메서드는 예외를 삼키지 않는다 — 저장 실패를 best-effort 로 흡수하는 책임은
     * <b>호출부</b>({@link AiProxyService#ragChat})에 있다(#1593). 여기서 잡으면 이 {@code @Transactional}
     * 이 이미 rollback-only 로 마킹된 뒤라 커밋 시점에 {@code UnexpectedRollbackException} 으로 다시
     * 터진다. 예외를 프록시 경계 밖으로 그대로 던져야 저장 트랜잭션만 롤백되고 답변 반환이 살아남는다.
     */
    @Transactional
    public void saveConversation(Long sessionId, String query, RagChatResponse data) {
        chatMessageRepository.save(ChatMessage.createText(sessionId, ChatSenderType.USER, query));
        ChatMessage botMessage = chatMessageRepository.save(
                ChatMessage.createText(sessionId, ChatSenderType.BOT, data.answer()));

        if (data.sources() == null) {
            return;
        }
        // chat_message_citations 의 unique(message_id, document_id, chunk_ref) 위반 선제 차단(#1593) —
        // 같은 봇 메시지 안에서 동일 (documentId, chunkRef) 가 두 번 인용되면(리랭킹 결과에 같은 청크가
        // 중복 등장) DataIntegrityViolationException 이 난다. message_id 는 이 루프 안에서 고정이므로
        // (documentId, chunkRef) 만으로 중복을 판정한다. locator/snippet 이 달라도 같은 청크면 한 건만
        // 남긴다 — 어차피 DB 제약이 두 번째 행을 받아주지 않는다.
        Set<String> seenChunks = new HashSet<>();
        for (RagChatResponse.SourceCitation source : data.sources()) {
            Long documentId = parseDocumentId(source.docId());
            if (documentId == null) {
                continue;
            }
            // documentId 는 숫자라 ':' 앞뒤 값이 섞일 여지가 없다(키 모호성 없음).
            if (!seenChunks.add(documentId + ":" + source.chunkRef())) {
                log.warn("RAG 출처 중복 인용 — citation 저장 생략: documentId={}, chunkRef={}",
                        documentId, source.chunkRef());
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
