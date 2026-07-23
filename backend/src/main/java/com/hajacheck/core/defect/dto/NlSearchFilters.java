package com.hajacheck.core.defect.dto;

import java.util.List;

/**
 * 하자 자연어 검색 필터 조건 — openapi.yaml NlSearchFilters(HAJA-120/179~183).
 * confidenceMin만 camelCase — 프론트 기존 수동 필터(DefectListFilters)와 표기 일관성 유지 결정
 * (docs/design/ai/nl_search_filter_schema.md §1.3), 나머지 필드는 상위 {@link NlSearchResult} 참고.
 */
public record NlSearchFilters(
        List<String> type,
        List<String> grade,
        List<String> status,
        Double confidenceMin) {
}
