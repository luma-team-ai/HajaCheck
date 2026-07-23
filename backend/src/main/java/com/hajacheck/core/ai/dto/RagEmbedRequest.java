package com.hajacheck.core.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * FastAPI {@code POST /ai/rag-documents/embed} 요청 바디 — #22/HAJA-35. JSON 키는 FastAPI 계약과
 * 동일하게 snake_case 유지(DefectExplainRequest와 동일 패턴). RagDocumentService가 PDF에서 추출한
 * 텍스트와 rag_documents 메타데이터를 그대로 실어 보낸다 — 결측 필드(effectiveDate 등)는 null로 두면
 * Jackson이 키 자체를 생략한다(rag_chroma_schema.md §3 "결측값: 키 자체를 생성하지 않는다").
 */
public record RagEmbedRequest(
        @JsonProperty("doc_id") String docId,
        String title,
        @JsonProperty("doc_type") String docType,
        @JsonProperty("target_collection") String targetCollection,
        String text,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("effective_date") String effectiveDate,
        @JsonInclude(JsonInclude.Include.NON_NULL) String publisher,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("authored_at") String authoredAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("verification_status") String verificationStatus) {
}
