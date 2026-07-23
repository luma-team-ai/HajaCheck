// POST /api/defects/nl-search 응답 타입 — openapi.yaml NlSearchResult(HAJA-120)와 1:1.
// unsupported_terms/clarifying_question/interpretation_confidence는 계약상 snake_case 그대로 노출된다
// (docs/design/ai/nl_search_filter_schema.md §1.3 — filters.confidenceMin만 예외적으로 camelCase).

import type { DefectGrade, DefectStatus, DefectType } from './types';

export interface NlSearchFilters {
  type: DefectType[];
  grade: DefectGrade[];
  status: DefectStatus[];
  confidenceMin: number | null;
}

export interface NlSearchResult {
  filters: NlSearchFilters;
  unsupported_terms: string[];
  clarifying_question: string | null;
  interpretation_confidence: number;
}
