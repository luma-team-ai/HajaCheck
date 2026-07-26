// 대시보드 목 데이터 — MSW 핸들러(api/dashboardApi.handlers.ts) 전용이다.
//
// ⚠️ 훅(useDashboardSummary/useGradeDistribution/usePendingPriority/useRecentInspections)은
//    PR #321(dev-03-01)에서 404 폴백(fetchWithFallback)을 제거하고 실 API(/api/dashboard/*)를
//    직접 호출한다. 따라서 이 목들은 프로덕션 데이터 경로에 관여하지 않는다.
//    그럼에도 유지하는 이유는 팀의 FE-우선 개발 사이클(MSW로 화면 먼저 → BE 연동) 때문이다.
//    → 훅에서 import가 사라졌다고 "고아 export"로 오해하지 말 것(#328 오탐 사례).
import type {
  AiBriefing,
  DashboardFacilityOption,
  DashboardSummary,
  GradeDistributionItem,
  PendingPriorityItem,
  RecentInspectionItem,
  UpcomingInspectionItem,
} from '../types';

// MSW 응답용 예시 값 — Figma 캡처(dev-03-01) 수치 기준.
// (BE 집계 API는 PR #222로 구현 완료 — 과거 "백엔드 미구현" 주석은 stale이라 정리)
export const mockDashboardSummary: DashboardSummary = {
  // Figma 시안(dev-03-01)과 동일: 4개 카드 모두 ↗8%
  totalFacilities: 24,
  totalFacilitiesChangeRate: 8,
  monthlyAnalyzed: 1284,
  monthlyAnalyzedChangeRate: 8,
  pendingReview: 37,
  pendingReviewChangeRate: 8,
  pendingAction: 12,
  pendingActionChangeRate: 8,
};

export const mockGradeDistribution: GradeDistributionItem[] = [
  { grade: 'A', percent: 45 },
  { grade: 'B', percent: 25 },
  { grade: 'C', percent: 15 },
  { grade: 'D', percent: 10 },
  { grade: 'E', percent: 5 },
];

function hoursAgo(hours: number): string {
  return new Date(Date.now() - hours * 60 * 60 * 1000).toISOString();
}

export const mockPendingPriority: PendingPriorityItem[] = [
  {
    id: 1,
    grade: 'E',
    title: '철근 노출',
    location: '여의도 파크센터 - 지하 3층 기둥',
    occurredAt: hoursAgo(2),
  },
  {
    id: 2,
    grade: 'D',
    title: '균열 (관통)',
    location: '강남 오피스타워 A동 - 외벽 북측',
    occurredAt: hoursAgo(5),
  },
  {
    id: 3,
    grade: 'D',
    title: '누수·백태',
    location: '한강대교 북단 - 교각 하부 3번',
    occurredAt: hoursAgo(26),
  },
  // BE PendingPriorityResponse.grade는 AI 등급 미분류 하자에서 null — 로컬 MSW에서도 '-' 배지를
  // 육안 확인할 수 있도록 미분류 샘플을 1건 포함한다(HAJA-17 dev-03-01).
  {
    id: 4,
    grade: null,
    title: '도장 손상',
    location: '판교 테크노밸리 - 외벽 동측',
    occurredAt: hoursAgo(31),
  },
];

export const mockRecentInspections: RecentInspectionItem[] = [
  {
    id: 1,
    facilityName: '여의도 파크센터',
    inspectedAt: '2026-07-13',
    inspector: '김현수',
    defectCount: 6,
    status: '검수대기',
  },
  {
    id: 2,
    facilityName: '강남 오피스타워',
    inspectedAt: '2026-07-12',
    inspector: '이서연',
    defectCount: 3,
    status: '조치대기',
  },
  {
    id: 3,
    facilityName: '판교 테크노밸리',
    inspectedAt: '2026-07-12',
    inspector: '박도윤',
    defectCount: 0,
    status: '분석중',
  },
  {
    id: 4,
    facilityName: '송도 물류센터',
    inspectedAt: '2026-07-10',
    inspector: '최지우',
    defectCount: 9,
    status: '완료',
  },
  {
    id: 5,
    facilityName: '수원 스마트팩토리',
    inspectedAt: '2026-07-09',
    inspector: '정민준',
    defectCount: 2,
    status: '완료',
  },
];

// "최근 점검 전체보기"(신규, GET /api/dashboard/recent-inspections/search) 전용 목 데이터 —
// 페이지네이션이 화면상 실제로 동작하는 걸 눈으로 확인할 수 있도록 10건보다 많이 둔다(22건).
// 앞 5건은 위젯 목(mockRecentInspections)과 동일값을 유지해 "필터 없이 호출하면 위젯과 동일한
// 상위 항목"이라는 계약을 목 데이터에서도 시각적으로 대조할 수 있게 한다.
export const mockRecentInspectionsFull: RecentInspectionItem[] = [
  ...mockRecentInspections,
  {
    id: 6,
    facilityName: '부산 마린센터',
    inspectedAt: '2026-07-08',
    inspector: '한지민',
    defectCount: 4,
    status: '검수대기',
  },
  {
    id: 7,
    facilityName: '대전 테크노파크',
    inspectedAt: '2026-07-08',
    inspector: '김현수',
    defectCount: 1,
    status: '조치대기',
  },
  {
    id: 8,
    facilityName: '광주 이노밸리',
    inspectedAt: '2026-07-07',
    inspector: '이서연',
    defectCount: 0,
    status: '분석중',
  },
  {
    id: 9,
    facilityName: '울산 산업단지 3동',
    inspectedAt: '2026-07-07',
    inspector: '박도윤',
    defectCount: 8,
    status: '완료',
  },
  {
    id: 10,
    facilityName: '세종 시청사',
    inspectedAt: '2026-07-06',
    inspector: '최지우',
    defectCount: 2,
    status: '완료',
  },
  {
    id: 11,
    facilityName: '인천 국제터미널',
    inspectedAt: '2026-07-06',
    inspector: '정민준',
    defectCount: 5,
    status: '조치대기',
  },
  {
    id: 12,
    facilityName: '창원 산업센터',
    inspectedAt: '2026-07-05',
    inspector: '한지민',
    defectCount: 0,
    status: '분석중',
  },
  {
    id: 13,
    facilityName: '전주 한옥마을 안전센터',
    inspectedAt: '2026-07-05',
    inspector: '김현수',
    defectCount: 3,
    status: '검수대기',
  },
  {
    id: 14,
    facilityName: '청주 물류단지',
    inspectedAt: '2026-07-04',
    inspector: '이서연',
    defectCount: 7,
    status: '완료',
  },
  {
    id: 15,
    facilityName: '천안 스마트타운',
    inspectedAt: '2026-07-04',
    inspector: '박도윤',
    defectCount: 1,
    status: '조치대기',
  },
  {
    id: 16,
    facilityName: '여수 화학단지 2공장',
    inspectedAt: '2026-07-03',
    inspector: '최지우',
    defectCount: 0,
    status: '분석중',
  },
  {
    id: 17,
    facilityName: '포항 제철단지',
    inspectedAt: '2026-07-03',
    inspector: '정민준',
    defectCount: 6,
    status: '검수대기',
  },
  {
    id: 18,
    facilityName: '거제 조선소 A안벽',
    inspectedAt: '2026-07-02',
    inspector: '한지민',
    defectCount: 2,
    status: '완료',
  },
  {
    id: 19,
    facilityName: '제주 공항 물류센터',
    inspectedAt: '2026-07-02',
    inspector: '김현수',
    defectCount: 4,
    status: '조치대기',
  },
  {
    id: 20,
    facilityName: '춘천 데이터센터',
    inspectedAt: '2026-07-01',
    inspector: '이서연',
    defectCount: 0,
    status: '분석중',
  },
  {
    id: 21,
    facilityName: '강릉 해양센터',
    inspectedAt: '2026-06-30',
    inspector: '박도윤',
    defectCount: 3,
    status: '검수대기',
  },
  {
    id: 22,
    facilityName: '안동 문화센터',
    inspectedAt: '2026-06-29',
    inspector: '최지우',
    defectCount: 5,
    status: '완료',
  },
];

// "최근 점검 전체보기" 필터 바의 시설물 select 옵션 목 — defect feature의
// mockInspectionFacilityOptions와 동일 패턴(feature 간 직접 import 금지, 로컬 복제).
export const mockDashboardFacilityOptions: DashboardFacilityOption[] = [
  { id: 1, name: '여의도 파크센터' },
  { id: 2, name: '강남 오피스타워' },
  { id: 3, name: '판교 테크노밸리' },
  { id: 4, name: '송도 물류센터' },
  { id: 5, name: '수원 스마트팩토리' },
];

function daysFromNowIsoDate(days: number): string {
  const date = new Date(Date.now() + days * 24 * 60 * 60 * 1000);
  return date.toISOString().slice(0, 10);
}

// Figma 시안(dev-03-02) D-7/D-1/D-42 예시값 기준 — "점검 유형"·"이전 최고등급"은 BE 미제공이라 제외(#543)
export const mockUpcomingInspections: UpcomingInspectionItem[] = [
  {
    facilityId: 1,
    facilityName: '한강대교 북단',
    nextInspectionDueAt: daysFromNowIsoDate(7),
    dDay: 7,
    inspectionCycleMonths: 12,
  },
  {
    facilityId: 2,
    facilityName: '강남 오피스타워 A동',
    nextInspectionDueAt: daysFromNowIsoDate(1),
    dDay: 1,
    inspectionCycleMonths: 6,
  },
  {
    facilityId: 3,
    facilityName: '판교 R&D 센터',
    nextInspectionDueAt: daysFromNowIsoDate(42),
    dDay: 42,
    inspectionCycleMonths: 12,
  },
];

export const mockAiBriefing: AiBriefing = {
  briefing:
    '이번 주 등록된 하자는 총 45건으로 지난 주 대비 12% 감소했습니다. 주요 발생 유형은 균열이며, D등급 이상 중대 결함이 3건 발견되어 즉각적인 조치가 권장됩니다.',
  recommendation: 'D등급 이상 3건 우선 조치 권장.',
  facts: {
    thisWeekDefects: 45,
    lastWeekDefects: 51,
    changePct: 12,
    trend: '감소',
    topDefectType: '균열',
    criticalDefects: 3,
  },
};
