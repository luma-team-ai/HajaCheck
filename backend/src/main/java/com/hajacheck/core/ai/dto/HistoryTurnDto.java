package com.hajacheck.core.ai.dto;

/**
 * FastAPI {@code POST /ai/rag-chat} 로 전달하는 대화 이력 한 턴(#1493/HAJA-657).
 *
 * <p>세션의 과거 질문/답변 텍스트만 담는다 — {@code chat_message_citations}(출처)는 여기 포함하지
 * 않는다(설계 §3 "질문/답변 텍스트만"). {@link AiProxyService} 가 최근 3턴만 추려 담아 보낸다.
 */
public record HistoryTurnDto(String question, String answer) {
}
