package com.hajacheck.core.ai.service;

import com.hajacheck.counsel.entity.ChatMessage;
import com.hajacheck.counsel.entity.ChatSenderType;
import com.hajacheck.counsel.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RAG 대화의 질문/답변 메시지 쌍만 저장하는 writer(#1593).
 *
 * <p>출처(citation) 저장과 물리 트랜잭션을 분리하기 위해 별도 빈으로 뒀다 — 알려진 저장 실패 경로는
 * 전부 {@code chat_message_citations} 쪽(FK·unique·NOT NULL·길이)인데, 한 트랜잭션에 묶여 있으면
 * 출처 한 건 때문에 질문·답변까지 롤백된다. 분리해두면 출처만 유실되고 대화 이력은 남는다.
 *
 * <p>USER/BOT 두 건은 <b>같은 트랜잭션</b>이어야 한다 — 질문만 남고 답변이 없는 반쪽 이력은
 * {@code buildRecentHistory()} 의 (질문,답변) 페어링을 어긋나게 만든다.
 *
 * <p><b>전파를 {@code REQUIRES_NEW} 로 명시하는 이유</b>: best-effort 보장은 "저장이 항상 독립 물리
 * 트랜잭션"이라는 전제에 걸려 있다. 기본값 {@code REQUIRED} 로 두면 훗날 누군가
 * {@code @Transactional} 이 걸린 경로에서 {@link AiProxyService#ragChat} 을 호출하는 순간 이 저장이
 * 바깥 트랜잭션에 참여하고, 제약 위반이 바깥을 rollback-only 로 오염시켜 호출자의 커밋이
 * {@code UnexpectedRollbackException} 으로 터진다(= 이번 픽스가 막으려던 실패 모드의 부활).
 * 현재 호출 경로엔 바깥 트랜잭션이 없어 런타임 동작·커넥션 점유는 그대로다. 같은 종류의 전제를
 * 구조로 못박은 선례는 {@code RagDocumentService.delete()} 의 {@code NOT_SUPPORTED}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagChatMessageWriter {

    private final ChatMessageRepository chatMessageRepository;

    /** @return 저장된 봇 메시지 식별자 — 출처 저장이 이 id 를 FK 로 참조한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long saveMessages(Long sessionId, String query, String answer) {
        chatMessageRepository.save(ChatMessage.createText(sessionId, ChatSenderType.USER, query));
        ChatMessage botMessage = chatMessageRepository.save(
                ChatMessage.createText(sessionId, ChatSenderType.BOT, answer));
        return botMessage.getId();
    }
}
