package com.hajacheck.counsel.service;

import com.hajacheck.counsel.dto.ChatSessionCreateRequest;
import com.hajacheck.counsel.dto.ChatSessionMessageResponse;
import com.hajacheck.counsel.dto.ChatSessionResponse;
import com.hajacheck.counsel.entity.ChatMessage;
import com.hajacheck.counsel.entity.ChatSession;
import com.hajacheck.counsel.entity.ChatSessionType;
import com.hajacheck.counsel.repository.ChatMessageRepository;
import com.hajacheck.counsel.repository.ChatSessionRepository;
import com.hajacheck.core.rag.entity.ChatMessageCitation;
import com.hajacheck.core.rag.repository.ChatMessageCitationRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채팅 세션 API(#1467/HAJA-647) — RAG 챗봇이 대화 맥락을 유지하기 위한 세션 생성·이력 조회.
 *
 * <p><b>인가 원칙</b>: 세션 식별자는 요청자가 보내는 입력값이므로 신뢰하지 않는다. 모든 조회는
 * {@code (id, userId)} 복합 조건으로 수행해 cross-user 접근(IDOR)을 차단하고, 실패는 미존재/타인 소유를
 * 구분하지 않는 단일 403({@link ErrorCode#CHAT_SESSION_FORBIDDEN})으로 응답한다 — 세션 존재 여부를
 * 흘리지 않기 위함(COUNSEL_TICKET_FORBIDDEN 관례와 동일).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatSessionService {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatMessageCitationRepository chatMessageCitationRepository;

    /**
     * @param userId 소유자 — 컨트롤러가 {@code @AuthenticationPrincipal} 에서만 취득해 전달한다(바디 금지).
     */
    @Transactional
    public ChatSessionResponse createSession(Long userId, ChatSessionCreateRequest request) {
        ChatSession session = chatSessionRepository.save(
                ChatSession.start(userId, request.sessionType()));
        return ChatSessionResponse.from(session);
    }

    /** 세션 이력 조회 — 소유자만 가능하며, 타인 세션이거나 없는 세션이면 403. */
    public List<ChatSessionMessageResponse> findMessages(Long userId, Long sessionId) {
        ChatSession session = getOwnedSession(userId, sessionId);
        List<ChatMessage> messages =
                chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        if (messages.isEmpty()) {
            return List.of();
        }

        Map<Long, List<ChatSessionMessageResponse.Citation>> citationsByMessageId =
                loadCitations(messages);
        return messages.stream()
                .map(message -> ChatSessionMessageResponse.from(
                        message,
                        citationsByMessageId.getOrDefault(message.getId(), List.of())))
                .toList();
    }

    /**
     * 세션 소유(+ 선택적으로 세션 유형) 검증. AI 프록시({@code /api/ai/rag-chat})도 이 메서드로 검증해
     * 인가 로직이 한 곳에만 존재하도록 한다.
     *
     * @param requiredType null 이면 유형 검증을 생략한다. 값이 있으면 불일치 시에도 동일한 403.
     */
    public ChatSession getOwnedSession(Long userId, Long sessionId, ChatSessionType requiredType) {
        ChatSession session = chatSessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_SESSION_FORBIDDEN));
        if (requiredType != null && session.getSessionType() != requiredType) {
            throw new BusinessException(ErrorCode.CHAT_SESSION_FORBIDDEN);
        }
        return session;
    }

    public ChatSession getOwnedSession(Long userId, Long sessionId) {
        return getOwnedSession(userId, sessionId, null);
    }

    private Map<Long, List<ChatSessionMessageResponse.Citation>> loadCitations(
            List<ChatMessage> messages) {
        List<Long> messageIds = messages.stream().map(ChatMessage::getId).toList();
        return chatMessageCitationRepository.findByMessageIdIn(messageIds).stream()
                .collect(Collectors.groupingBy(
                        ChatMessageCitation::getMessageId,
                        Collectors.mapping(ChatSessionMessageResponse.Citation::from,
                                Collectors.toList())));
    }
}
