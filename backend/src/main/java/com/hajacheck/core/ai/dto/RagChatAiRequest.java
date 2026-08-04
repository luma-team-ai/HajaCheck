package com.hajacheck.core.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * FastAPI {@code POST /ai/rag-chat} 요청 바디(contract.md, HAJA-32, #467).
 *
 * <p>FastAPI 요청 스키마는 {@code session_id}(선택, 세션 상관용 선점 필드)도 받지만 "현재 파이프라인은
 * 이 값을 사용하지 않는다"(contract.md §"내부 호출 계약") — 이 프록시는 아예 보내지 않는다(선택 필드라
 * 생략해도 FastAPI 쪽 계약 위반 아님).
 *
 * @param companyId 요청자의 회사 식별자(#1584). 컨트롤러가 {@code @AuthenticationPrincipal} 에서만
 *                  취득해 전달한다 — 요청 바디에서 받지 않는다({@code userId} 와 동일 규약).
 *                  AI 서버는 이 값으로 시맨틱 캐시의 조회·저장 스코프를 회사 단위로 제한한다.
 *                  회사 미소속 개인회원은 {@code null} 이며, 그때 AI 서버는 시맨틱 캐시를 조회도
 *                  저장도 하지 않는다(fail-closed — 필터 없는 전역 조회 폴백 금지).
 * @param history 세션이 있을 때 {@link AiProxyService} 가 최근 3턴만 추려 담는 대화 이력(#1493/HAJA-657).
 *                세션이 없으면(단발 질의) 빈 리스트.
 */
public record RagChatAiRequest(String question,
                               @JsonProperty("company_id") Long companyId,
                               List<HistoryTurnDto> history) {

    /** 이력 없는(단발 질의) 호출부 편의 생성자 — 기존 계약·테스트 호환. */
    public RagChatAiRequest(String question, Long companyId) {
        this(question, companyId, List.of());
    }
}
