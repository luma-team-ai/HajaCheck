// POST /api/defects/nl-search 응답 타입 — 기존 하자 축에 점검 유형·상태·날짜·회차·전체 하자 건수
// 축을 확장한다(HAJA-540). 점검 축은 단계적 배포 중 구버전 응답도 안전하게 처리하도록 optional이다.
// unsupported_terms/clarifying_question/interpretation_confidence는 계약상 snake_case 그대로 노출된다
// (필터 내부 필드는 camelCase).

import type {
  DefectGrade,
  DefectStatus,
  DefectType,
  InspectionStatus,
  InspectionType,
} from './types';

export interface NlSearchFilters {
  type: DefectType[];
  grade: DefectGrade[];
  status: DefectStatus[];
  confidenceMin: number | null;
  inspectionType?: InspectionType[] | null;
  inspectionStatus?: InspectionStatus[] | null;
  inspectionDateFrom?: string | null;
  inspectionDateTo?: string | null;
  roundNoMin?: number | null;
  roundNoMax?: number | null;
  defectCountMin?: number | null;
  defectCountMax?: number | null;
}

export interface NlSearchResult {
  filters: NlSearchFilters;
  unsupported_terms: string[];
  clarifying_question: string | null;
  interpretation_confidence: number;
}
