package com.hajacheck.core.ai.dto;

import java.util.List;

/**
 * FastAPI {@code POST /ai/rag-chat} 요청 바디(contract.md, HAJA-32, #467).
 *
 * <p>FastAPI 요청 스키마는 {@code session_id}(선택, 세션 상관용 선점 필드)도 받지만 "현재 파이프라인은
 * 이 값을 사용하지 않는다"(contract.md §"내부 호출 계약") — 이 프록시는 아예 보내지 않는다(선택 필드라
 * 생략해도 FastAPI 쪽 계약 위반 아님).
 *
 * @param history 세션이 있을 때 {@link AiProxyService} 가 최근 3턴만 추려 담는 대화 이력(#1493/HAJA-657).
 *                세션이 없으면(단발 질의) 빈 리스트.
 */
public record RagChatAiRequest(String question, List<HistoryTurnDto> history) {

    /** 이력 없는(단발 질의) 호출부 편의 생성자 — 기존 계약·테스트 호환. */
    public RagChatAiRequest(String question) {
        this(question, List.of());
    }
}
