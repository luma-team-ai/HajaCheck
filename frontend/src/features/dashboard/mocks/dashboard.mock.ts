import type {
  AiBriefing,
  DashboardSummary,
  GradeDistributionItem,
  PendingPriorityItem,
  RecentInspectionItem,
} from '../types';

// ponytail: 백엔드 대시보드 집계 엔드포인트 미구현 — 계약(#10) 확정 전까지 Figma 캡처(dev-03-01) 수치 기준 mock
export const mockDashboardSummary: DashboardSummary = {
  totalFacilities: 24,
  totalFacilitiesChangeRate: 8,
  monthlyAnalyzed: 1284,
  monthlyAnalyzedChangeRate: 12,
  pendingReview: 37,
  pendingReviewChangeRate: -4,
  pendingAction: 12,
  pendingActionChangeRate: -2,
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
    title: '균열 (폭 0.8mm)',
    location: '강남 오피스타워 - 5층 슬라브',
    occurredAt: hoursAgo(5),
  },
  {
    id: 3,
    grade: 'D',
    title: '누수·백태',
    location: '판교 테크노밸리 - 옥상 방수층',
    occurredAt: hoursAgo(9),
  },
  {
    id: 4,
    grade: 'E',
    title: '박리·박락',
    location: '송도 물류센터 - 지상 1층 기둥',
    occurredAt: hoursAgo(26),
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
