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

export interface ReportOptions {
  sections?: string[];
  includePhoto?: boolean;
}

export interface ReportContent {
  overview: ReportOverview;
  summary: ReportSummary;
  detail: ReportDetail;
  recommendation: ReportRecommendation;
  reportOptions?: ReportOptions;
}

// ---------------------------------------------------------------------------
// 보고서 목록 / 이력 관리 (#463, 사이드바 "보고서" 최상위 메뉴 — 회사 스코프 전체 보고서 목록)
// GET /api/reports, /api/reports/summary — 백엔드 미구현 시 hybrid 훅이 404를 목으로 폴백한다.
// ---------------------------------------------------------------------------

export type ReportListStatus = 'DRAFT' | 'FINALIZED';

// 등급 색상은 Figma 시안(A=빨강...D=초록)이 아니라 SOT 등급 의미(A=양호/E=중대)를 따른다 —
// 2026-07-21 Figma 등급 색상 3곳 불일치 감사에서 Figma가 아니라 SOT 설계문서가 기준으로
// 확정됐다. defect feature GRADE_CLASSES와 동일 색상 체계(A=emerald...E=red).
export type ReportGrade = 'A' | 'B' | 'C' | 'D' | 'E';
export type ReportGradeDistribution = Partial<Record<ReportGrade, number>>;

export interface ReportListItem {
  id: number;
  inspectionId: number;
  facilityId: number;
  facilityName: string;
  roundNo: number;
  // 보고서명(title)은 서버 필드가 아니다 — reports 테이블에 title 컬럼이 없다(V1 baseline schema).
  // mypage feature formatReportTitle(#844)과 동일하게 facilityName+roundNo+updatedAt으로 프론트가
  // 조립한다(utils/reportListFormat.ts#formatReportListTitle).
  gradeDistribution: ReportGradeDistribution;
  status: ReportListStatus;
  version: number;
  updatedAt: string; // ISO datetime
  pdfUrl: string | null; // FINALIZED에서만 값 존재
}

export interface ReportListSummary {
  totalCount: number;
  finalizedCount: number;
  draftCount: number;
  issuedThisMonthCount: number; // 이번 달 FINALIZED 건수
}

export type ReportListPeriod = '1M' | '3M' | '6M' | 'ALL';

export interface ReportListFilters {
  page?: number;
  size?: number;
  facilityId?: number;
  status?: ReportListStatus;
  query?: string;
  period?: ReportListPeriod;
}

// 목록 필터의 시설물 select 옵션 — GET /api/facilities 재사용(defect feature의 동일 선례,
// feature 간 직접 import 금지 컨벤션에 따라 로컬로 재정의).
export interface ReportFacilityOption {
  id: number;
  name: string;
}

// content가 아직 위 구조를 갖췄는지 런타임 가드(초안 생성 직후 형태가 다를 가능성에 방어) —
// 얕은 필드 존재 검사만 수행한다(과설계 금지).
export function isReportContent(value: unknown): value is ReportContent {
  if (!value || typeof value !== 'object') return false;
  const v = value as Record<string, unknown>;
  const detail = v.detail as Record<string, unknown> | null;
  const rec = v.recommendation as Record<string, unknown> | null;
  return (
    typeof v.overview === 'object' &&
    v.overview !== null &&
    typeof v.summary === 'object' &&
    v.summary !== null &&
    typeof v.detail === 'object' &&
    v.detail !== null &&
    Array.isArray(detail?.items) &&
    typeof v.recommendation === 'object' &&
    v.recommendation !== null &&
    Array.isArray(rec?.items)
  );
}
