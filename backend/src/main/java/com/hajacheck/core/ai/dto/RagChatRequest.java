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
 * <p>{@code session_id}는 세션 소유·{@code session_type='RAG'} 검증(contract.md §"내부 호출 계약")이
 * {@code /api/chat-sessions} 미구현으로 후속 이슈로 분리돼 있어 이번 프록시 범위에서는 받지 않는다.
 *
 * <p>{@code max=2000}은 계약에 명시된 상한은 아니며, 다른 프록시 요청 필드(예: DefectExplainRequest의
 * location max=200)와 동일한 취지로 무제한 자유 텍스트 입력이 LLM 다운스트림 비용·부하를 키우는 것을
 * 막기 위한 방어적 상한이다(리뷰에서 재확인 대상).
 */
public record RagChatRequest(@NotBlank @Size(max = 2000) String query) {
}
