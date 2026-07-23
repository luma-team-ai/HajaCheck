// AI 서버 report_chain.py의 Pydantic 스키마(ReportOverview/ReportSummary/ReportDetail/
// ReportRecommendation)와 1:1 대응하는 프론트 타입. content JSON 구조가 이 스키마 그대로 온다.

export interface ReportOverview {
  purpose: string; // 점검 목적
  facility_summary: string; // 시설물 개요
  scope: string; // 점검 범위·대상 부위
}

export interface ReportSummary {
  overall_opinion: string; // 종합 의견
  total_count: number; // 총 하자 수
  count_by_grade: Record<string, number>; // 등급별 개수 {"A": n, ...}
  key_findings: string[]; // 주요 발견사항
}

export interface DefectDetailItem {
  defect_type: string;
  location: string;
  severity_grade: string;
  description: string;
  cause: string;
}

export interface ReportDetail {
  items: DefectDetailItem[];
}

export interface RecommendationItem {
  target: string;
  method: string;
  priority: string;
  legal_basis: string;
  legal_basis_verified: boolean;
}

export interface ReportRecommendation {
  items: RecommendationItem[];
  monitoring_points: string[];
}

export interface ReportContent {
  overview: ReportOverview;
  summary: ReportSummary;
  detail: ReportDetail;
  recommendation: ReportRecommendation;
}

// content가 아직 위 구조를 갖췄는지 런타임 가드(초안 생성 직후 형태가 다를 가능성에 방어) —
// 얕은 필드 존재 검사만 수행한다(과설계 금지).
export function isReportContent(value: unknown): value is ReportContent {
  if (!value || typeof value !== 'object') return false;
  const v = value as Record<string, unknown>;
  return (
    typeof v.overview === 'object' &&
    v.overview !== null &&
    typeof v.summary === 'object' &&
    v.summary !== null &&
    typeof v.detail === 'object' &&
    v.detail !== null &&
    typeof v.recommendation === 'object' &&
    v.recommendation !== null
  );
}
