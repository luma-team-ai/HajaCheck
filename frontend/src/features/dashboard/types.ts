// 대시보드 개요 화면 — HAJA-17(dev-03-01) — PRD §4 대시보드 기준
// feature 간 직접 import 금지(React_코드_컨벤션.md §1) — DefectGrade는 inspection과 별개로 로컬 정의

export type DefectGrade = 'A' | 'B' | 'C' | 'D' | 'E';

export interface DashboardSummary {
  totalFacilities: number;
  totalFacilitiesChangeRate: number;
  monthlyAnalyzed: number;
  monthlyAnalyzedChangeRate: number;
  pendingReview: number;
  pendingReviewChangeRate: number;
  pendingAction: number;
  pendingActionChangeRate: number;
}

export interface GradeDistributionItem {
  grade: DefectGrade;
  percent: number;
}

export interface PendingPriorityItem {
  id: number;
  grade: DefectGrade | null; // BE PendingPriorityResponse — 미분류 하자는 null 반환
  title: string;
  location: string;
  occurredAt: string; // ISO datetime — 발생 시각
}

export type InspectionStatus = '분석중' | '검수대기' | '조치대기' | '완료';

export interface RecentInspectionItem {
  id: number;
  facilityName: string;
  inspectedAt: string; // ISO date — 점검일
  inspector: string; // 담당자
  defectCount: number;
  status: InspectionStatus;
}

// ---------------------------------------------------------------------------
// "최근 점검 전체보기"(신규) — GET /api/dashboard/recent-inspections/search
// 기존 위젯(/dashboard/recent-inspections, 상위 10건 고정 배열)과는 별개 엔드포인트.
// contract.md §"대시보드 '최근 점검 전체보기' 페이지네이션+검색 API" 참고.
// ---------------------------------------------------------------------------

// GET .../search 쿼리 파라미터 — page는 Spring Data 관례대로 0-based
export interface RecentInspectionsSearchFilters {
  page?: number;
  size?: number;
  status?: InspectionStatus;
  /** 시설물 종류 카테고리(예: "건물") 접두 매칭 — 특정 시설물 select가 아니다(Figma 재대조, 2026-07-26) */
  facilityType?: string;
  query?: string;
}

// AI 주간 브리핑 — docs/design/ai/dashboard_briefing.md §4 출력 스키마와 1:1
export type BriefingTrend = '감소' | '증가' | '유지';

export interface AiBriefingFacts {
  thisWeekDefects: number;
  lastWeekDefects: number;
  changePct: number | null;
  trend: BriefingTrend;
  topDefectType: string;
  criticalDefects: number;
}

export interface AiBriefing {
  briefing: string;
  recommendation: string;
  facts: AiBriefingFacts;
}

// 다음 점검일 도래 위젯(dev-03-02, #469) — BE UpcomingInspectionResponse와 1:1.
// ⚠️ Figma 시안엔 "점검 유형"·"이전 최고등급"도 있으나 BE에 대응 데이터가 없어 이번 범위 제외(#543).
export interface UpcomingInspectionItem {
  facilityId: number;
  facilityName: string;
  nextInspectionDueAt: string; // ISO date(yyyy-MM-dd)
  dDay: number;
  inspectionCycleMonths: number | null;
}
