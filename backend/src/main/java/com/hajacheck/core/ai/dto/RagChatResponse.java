package com.hajacheck.core.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 프론트에 반환하는 고객지원 RAG 챗봇 답변(HAJA-32, #467) — FastAPI {@code RagAnswerData}(AIResponse.data)를
 * 그대로 매핑한다({@code DefectExplainResponse}와 동일하게 별도 가공 없음, {@link AiProxyService} 참고).
 *
 * <p>{@code sources[]} 필드는 프론트 계약(frontend/src/features/support/types.ts {@code SourceCitation})이
 * "재정의 금지 — wire(snake_case) 그대로 둔다"고 명시한 그대로다. 이 레포엔 전역 Jackson naming-strategy가
 * 없어(BriefingResponse.BriefingFacts 선례처럼 camelCase가 그대로 응답에 노출됨) {@code @JsonProperty}를
 * 양방향(AI 서버 응답 역직렬화 + 프론트 응답 직렬화)에 명시해야 snake_case가 유지된다
 * (DefectExplainRequest의 @JsonProperty 용례와 동일한 이유, {@code @JsonAlias}는 역직렬화만 바꿔 부적합).
 */
public record RagChatResponse(String answer, List<SourceCitation> sources) {

    public record SourceCitation(
            @JsonProperty("doc_id") String docId,
            String title,
            String collection,
            String locator,
            String snippet,
            @JsonProperty("chunk_ref") String chunkRef) {
    }
}
