// 통계 대시보드 목 데이터 — MSW 핸들러(api/statisticsApi.handlers.ts) 전용이다.
// dashboard/mocks/dashboard.mock.ts와 동일한 패턴 — feature 간 직접 import 금지 컨벤션에 따라
// 형태가 겹치더라도 이 feature 전용으로 별도 정의한다.
import type {
  DefectTypeDistributionItem,
  FacilitySummaryItem,
  FacilityTypeMonthlyHeatmapCell,
  GradeDistributionItem,
  MonthlyDefectTrendItem,
  StatisticsKpiSummary,
} from '../types';

// Figma 시안(node 77-1454) 목데이터 실측값과 동기화(2026-07-24).
export const mockStatisticsKpiSummary: StatisticsKpiSummary = {
  totalDefects: 1842,
  totalDefectsChangeRate: 12,
  avgResolutionDays: 4.2,
  avgResolutionDaysChangeRate: 0.8,
  actionCompletionRate: 76,
  actionCompletionRateChangeRate: 5,
  progressingDefects: 38,
  progressingDefectsChangeRate: 2,
};

// 최근 6개월치 예시 — PRD §2 라인차트. Figma는 1월~6월, Y축 0~400(100 단위), 마지막 달(6월)이
// 피크(342, 상시 노출 배지)로 끝난다(하락 없이 "현재가 최고점" 형태) — 2026-07-25 Figma 재확인 반영.
export const mockMonthlyDefectTrend: MonthlyDefectTrendItem[] = [
  { month: '2026-01', defectCount: 60 },
  { month: '2026-02', defectCount: 110 },
  { month: '2026-03', defectCount: 85 },
  { month: '2026-04', defectCount: 215 },
  { month: '2026-05', defectCount: 195 },
  { month: '2026-06', defectCount: 342 },
];

// PRD(v0.5, 2026-07-13 확정) FR-3: AI 탐지 클래스는 균열·박리박락·철근노출 3종으로 확정됐고
// 누수·백태·도장손상은 데이터 확보 문제로 이번 범위에서 제외됐다. 라벨은 features/defect/types.ts
// DEFECT_TYPE_LABEL(백엔드 DefectResponse.typeLabel 매핑)과 동일 문구를 쓴다.
export const mockDefectTypeDistribution: DefectTypeDistributionItem[] = [
  { type: '균열', count: 128 },
  { type: '박리·박락', count: 76 },
  { type: '철근 노출', count: 45 },
];

export const mockGradeDistribution: GradeDistributionItem[] = [
  { grade: 'A', percent: 40 },
  { grade: 'B', percent: 27 },
  { grade: 'C', percent: 18 },
  { grade: 'D', percent: 10 },
  { grade: 'E', percent: 5 },
];

// 시설물군별(건물/교량/도로/기타) × 최근 6개월 히트맵 — PRD §3.2 C안.
// facilities.type 접두어 파싱을 전제로 한 집계 결과를 흉내낸 목 데이터(파싱 자체는 백엔드가 수행).
export const mockFacilityTypeHeatmap: FacilityTypeMonthlyHeatmapCell[] = [
  // 건물
  { facilityTypeCategory: '건물', month: '2026-02', defectCount: 22 },
  { facilityTypeCategory: '건물', month: '2026-03', defectCount: 29 },
  { facilityTypeCategory: '건물', month: '2026-04', defectCount: 25 },
  { facilityTypeCategory: '건물', month: '2026-05', defectCount: 33 },
  { facilityTypeCategory: '건물', month: '2026-06', defectCount: 27 },
  { facilityTypeCategory: '건물', month: '2026-07', defectCount: 18 },
  // 교량
  { facilityTypeCategory: '교량', month: '2026-02', defectCount: 12 },
  { facilityTypeCategory: '교량', month: '2026-03', defectCount: 16 },
  { facilityTypeCategory: '교량', month: '2026-04', defectCount: 14 },
  { facilityTypeCategory: '교량', month: '2026-05', defectCount: 19 },
  { facilityTypeCategory: '교량', month: '2026-06', defectCount: 17 },
  { facilityTypeCategory: '교량', month: '2026-07', defectCount: 11 },
  // 도로
  { facilityTypeCategory: '도로', month: '2026-02', defectCount: 9 },
  { facilityTypeCategory: '도로', month: '2026-03', defectCount: 11 },
  { facilityTypeCategory: '도로', month: '2026-04', defectCount: 10 },
  { facilityTypeCategory: '도로', month: '2026-05', defectCount: 13 },
  { facilityTypeCategory: '도로', month: '2026-06', defectCount: 12 },
  { facilityTypeCategory: '도로', month: '2026-07', defectCount: 8 },
  // 기타
  { facilityTypeCategory: '기타', month: '2026-02', defectCount: 5 },
  { facilityTypeCategory: '기타', month: '2026-03', defectCount: 5 },
  { facilityTypeCategory: '기타', month: '2026-04', defectCount: 6 },
  { facilityTypeCategory: '기타', month: '2026-05', defectCount: 7 },
  { facilityTypeCategory: '기타', month: '2026-06', defectCount: 8 },
  { facilityTypeCategory: '기타', month: '2026-07', defectCount: 5 },
];

export const mockFacilitySummary: FacilitySummaryItem[] = [
  {
    facilityId: 1,
    facilityName: '여의도 파크센터',
    facilityType: '건물-정기-4개월',
    totalDefects: 18,
    latestGrade: 'C',
    lastInspectedAt: '2026-07-13',
  },
  {
    facilityId: 2,
    facilityName: '강남 오피스타워',
    facilityType: '건물-정밀-24개월',
    totalDefects: 9,
    latestGrade: 'B',
    lastInspectedAt: '2026-07-12',
  },
  {
    facilityId: 3,
    facilityName: '한강대교 북단',
    facilityType: '교량-정밀-12개월',
    totalDefects: 24,
    latestGrade: 'D',
    lastInspectedAt: '2026-07-10',
  },
  {
    facilityId: 4,
    facilityName: '판교 테크노밸리',
    facilityType: '건물-정기-4개월',
    totalDefects: 5,
    latestGrade: null,
    lastInspectedAt: '2026-07-09',
  },
  {
    facilityId: 5,
    facilityName: '수원 스마트팩토리',
    facilityType: '도로-정기-4개월',
    totalDefects: 3,
    latestGrade: 'A',
    lastInspectedAt: '2026-07-05',
  },
];
