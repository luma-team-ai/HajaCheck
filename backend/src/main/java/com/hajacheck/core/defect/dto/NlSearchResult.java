package com.hajacheck.core.defect.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 하자 자연어 검색 변환 결과 — openapi.yaml NlSearchResult(HAJA-120/179~183).
 * 내부 FastAPI 응답과 공개 API 응답이 동일 스키마를 공유하며, unsupported_terms/clarifying_question/
 * interpretation_confidence 3개 필드는 계약상 snake_case 그대로 노출한다(filters.confidenceMin만 예외).
 */
public record NlSearchResult(
        NlSearchFilters filters,
        @JsonProperty("unsupported_terms") List<String> unsupportedTerms,
        @JsonProperty("clarifying_question") String clarifyingQuestion,
        @JsonProperty("interpretation_confidence") Double interpretationConfidence) {
}
