package com.hajacheck.core.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 프론트가 보내는 고객지원 RAG 챗봇 질의 요청(HAJA-32, #467, contract.md {@code POST /ai/rag-chat}).
 *
 * <p>프론트 계약(frontend/src/features/support/types.ts {@code RagChatRequest})은 {@code query} 필드를
 * 쓰지만 FastAPI {@code POST /ai/rag-chat}은 {@code question}을 기대한다(contract.md §"요청") — 필드명이
 * 서로 달라 {@code DefectExplainRequest}처럼 동일 레코드를 그대로 AI 서버 호출 바디로 재사용할 수 없다.
 * {@link AiProxyService}가 이 값을 {@link RagChatAiRequest}로 변환해 전달한다.
 *
 * <p>{@code max=2000}은 계약에 명시된 상한은 아니며, 다른 프록시 요청 필드(예: DefectExplainRequest의
 * location max=200)와 동일한 취지로 무제한 자유 텍스트 입력이 LLM 다운스트림 비용·부하를 키우는 것을
 * 막기 위한 방어적 상한이다(리뷰에서 재확인 대상).
 *
 * @param sessionId {@code POST /api/chat-sessions}로 만든 채팅 세션 식별자(#1467/HAJA-647).
 *                  <b>nullable</b> — 없으면 기존과 동일한 단발 질의로 동작한다(회귀 없음). 값이 있으면
 *                  {@link AiProxyService}가 세션 소유자·{@code sessionType=RAG}를 검증하고 불일치 시
 *                  403을 던진다. 요청자가 보내는 입력값이므로 그대로 신뢰하지 않는다.
 *                  (검증 통과 후 이력을 FastAPI로 전달하거나 메시지를 저장하는 것은 후속 이슈 범위.)
 */
public record RagChatRequest(@NotBlank @Size(max = 2000) String query, Long sessionId) {

    /** 세션 없이 단발 질의하는 호출부(기존 계약·테스트) 편의 생성자. */
    public RagChatRequest(String query) {
        this(query, null);
    }
}
