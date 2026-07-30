// 통계 대시보드 화면 — HAJA-40(Jira), GitHub #27 — docs/_local/prds/STATISTICS/PRD.md §2 기준
// feature 간 직접 import 금지(React_코드_컨벤션.md §1) — DefectGrade는 dashboard와 별개로 로컬 정의

export type DefectGrade = 'A' | 'B' | 'C' | 'D' | 'E';

// KPI 스트립 4종 — 총 탐지 하자 / 평균 처리일 / 조치 완료율 / 진행성 하자 (PRD §2)
// ⚠️ KPI 카드의 정확한 레이아웃/아이콘/색상은 Figma 확인 전까지 보류 — 데이터 타입/바인딩만 우선 정의.
export interface StatisticsKpiSummary {
  totalDefects: number;
  totalDefectsChangeRate: number;
  avgResolutionDays: number;
  avgResolutionDaysChangeRate: number;
  actionCompletionRate: number; // % — 조치 완료율
  actionCompletionRateChangeRate: number;
  progressingDefects: number; // 진행성(미조치) 하자
  progressingDefectsChangeRate: number;
}

// 월별 하자 추이(라인차트) — PRD §2
export interface MonthlyDefectTrendItem {
  month: string; // 'yyyy-MM'
  defectCount: number;
}

// 유형별 분포(바차트) — PRD §2. PRD v0.5(2026-07-13 확정) FR-3: AI 탐지 클래스는 균열·박리박락·
// 철근노출 3종으로 확정됐고 누수·백태·도장손상은 데이터 확보 문제로 이번 범위에서 제외됐다.
// 라벨 표기는 features/defect/types.ts DEFECT_TYPE_LABEL(백엔드 DefectResponse.typeLabel 매핑)과
// 동일하게 "철근 노출"(공백 포함)을 쓴다.
export type DefectTypeCategory = '균열' | '박리·박락' | '철근 노출';

export interface DefectTypeDistributionItem {
  type: DefectTypeCategory;
  count: number;
}

// 등급별 분포(스택바+A~E 범례) — PRD §2. dashboard의 GradeDistributionItem과 형태는 같으나
// feature 간 직접 import 금지 컨벤션에 따라 별도 정의.
export interface GradeDistributionItem {
  grade: DefectGrade;
  percent: number;
}

// "시설물군별" 히트맵(시설물 유형 × 월별) — PRD §3.2 C안 확정 스펙.
// facilityTypeCategory는 facilities.type 원본 복합 문자열("건물-정기-4개월")의 접두어만 취한 것이며,
// 이 파싱은 백엔드가 수행해 API 응답에 카테고리로만 내려준다(PRD §3.3). 라벨 카피("시설물군별" 등)는 확정 전.
export type FacilityTypeCategory = '건물' | '교량' | '도로' | '기타';

export interface FacilityTypeMonthlyHeatmapCell {
  facilityTypeCategory: FacilityTypeCategory;
  month: string; // 'yyyy-MM'
  defectCount: number;
}

// 시설물 및 기간 검색/필터 쿼리 파라미터 타입 (HAJA-40, #27)
export interface StatisticsFilterParams {
  period?: string; // '3m' | '6m' | '1y'
  facilityId?: string; // 'all' 또는 특정 시설물 PK ID (예: '1')
}

// 시설물별 요약 테이블 — PRD §2. 컬럼 구성/정렬 방식은 Figma 확인 전까지 보류.
export interface FacilitySummaryItem {
  facilityId: number;
  facilityName: string;
  facilityType: string; // facilities.type 원본 문자열 그대로(파싱 전)
  totalDefects: number;
  latestGrade: DefectGrade | null;
  lastInspectedAt: string | null; // ISO date, 점검 이력이 없으면 null
}
