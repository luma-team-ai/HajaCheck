// AI 서버 report_chain.py의 Pydantic 스키마(ReportOverview/ReportSummary/ReportDetail/
// ReportRecommendation)와 1:1 대응하는 프론트 타입. content JSON 구조가 이 스키마 그대로 온다.

export interface ReportOverview {
  purpose: string; // 점검 목적
  facility_summary: string; // 시설물 개요
  scope: string; // 점검 범위·대상 부위
}

export interface ReportSummary {
  responsible_engineer_name?: string; // 책임기술자명(서명란 표기용, 수동 수정 가능)
  overall_opinion: string; // 종합 의견
  total_count: number; // 총 하자 수
  count_by_grade: Record<string, number>; // 등급별 개수 {"A": n, ...}
  key_findings: string[]; // 주요 발견사항
}

export interface DefectDetailItem {
  // 실제 Defect.id — ai-server가 confirmed_defects의 id를 그대로 echo한다. 프론트가 사진·bbox를
  // 배열 인덱스가 아니라 이 값으로 매칭한다(#1379). 구버전 저장분(이 필드 도입 전 생성)엔 없을 수
  // 있어 optional — 없으면 기존 인덱스 매칭으로 폴백한다.
  defect_id?: number;
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

// 백엔드가 생산할 수 없는 서식 섹션(제출문·참여기술진 명단·안전성평가·현장시험 등)은 편집
// 화면에서 수동 입력받는다. content_json은 jsonb opaque 컬럼이고 UpdateReportContentRequest도
// 문자열 하나만 받으므로(스키마 검증 없음), 아래 타입은 프론트 전용 확장이며 마이그레이션 없이
// 저장·왕복된다.
export interface SubmissionSectionData {
  recipient: string; // 수신자 (예: "서울특별시장 귀하")
  contractDate: string; // 계약 체결일
  companyName: string; // 발신 업체명
  companyAddress: string;
  representativeName: string; // 대표자(직인 서명란에 표기)
}

export interface ParticipantEntry {
  role: string; // 구분 (예: 사업책임기술인)
  name: string;
  qualification: string; // 자격 및 주요경력
  period: string; // 과업 참여기간
}

export interface ParticipantsSectionData {
  entries: ParticipantEntry[];
}

export interface GenericManualSectionData {
  body: string;
}

export type ManualSectionType =
  | 'submission'
  | 'overview-form'
  | 'inspection-result-repair'
  | 'participants'
  | 'summary-opinion'
  | 'member-condition-repair'
  | 'safety-assessment'
  | 'field-test'
  | 'facility-status'
  | 'location-drawing-photos';

export interface ManualSection {
  id: string;
  type: ManualSectionType;
  title: string;
  data: SubmissionSectionData | ParticipantsSectionData | GenericManualSectionData;
}

// 편집기 카드(고정 5종) + 수동 섹션을 함께 자유 순서로 배치하기 위한 키. 고정 섹션은 이 문자열
// 리터럴로, 수동 섹션은 ManualSection.id로 식별한다. 이 순서가 곧 PDF 출력 순서다.
// 'photos'는 확정 하자 이미지에서 자동 파생되는 데이터라 제출문/참여기술진 명단과 달리 사용자가
// 직접 입력하지 않는다 — 그래도 순서는 자유롭게 바꿀 수 있어야 하므로 manualSections가 아니라
// 고정 섹션으로 둔다(overview~recommendation과 동일한 취급).
export const FIXED_SECTION_KEYS = ['overview', 'summary', 'detail', 'recommendation', 'photos'] as const;
export type FixedSectionKey = (typeof FIXED_SECTION_KEYS)[number];
export type SectionKey = FixedSectionKey | string;

export interface ReportContent {
  overview: ReportOverview;
  summary: ReportSummary;
  detail: ReportDetail;
  recommendation: ReportRecommendation;
  reportOptions?: ReportOptions;
  manualSections?: ManualSection[];
  sectionOrder?: SectionKey[];
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
