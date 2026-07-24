package com.hajacheck.core.ai.dto;

/**
 * FastAPI {@code POST /ai/rag-chat} 요청 바디(contract.md, HAJA-32, #467).
 *
 * <p>FastAPI 요청 스키마는 {@code session_id}(선택, 세션 상관용 선점 필드)도 받지만 "현재 파이프라인은
 * 이 값을 사용하지 않는다"(contract.md §"내부 호출 계약") — 이 프록시는 세션·이력 연동 전이라 아예
 * 보내지 않는다(선택 필드라 생략해도 FastAPI 쪽 계약 위반 아님).
 */
public record RagChatAiRequest(String question) {
}
