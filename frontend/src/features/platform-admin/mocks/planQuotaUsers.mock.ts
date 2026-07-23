import type { PlanQuotaStats, PlanQuotaUser } from '../planQuota.types';

// 플랫폼 관리자 플랜·쿼터 관리 예제 데이터 — features/admin/mocks/planQuotaUsers.mock.ts(#508)를
// 그대로 옮긴 것(#625). 백엔드 /api/platform-admin/plans-quota(#624) 확정 전까지 화면 검증용.
// 계정은 전부 합성값(실데이터 아님). 기업 컬럼·플랜별 검색(사용자 지시, #624 후속) 검증을 위해
// 회사마다 다른 플랜(FREE/STANDARD/ENTERPRISE)을 섞어 둔다 — #508 목데이터는 전부 STANDARD 단일
// 회사였던 것과 다른 지점.

const QUOTA_LIMIT_BY_PLAN: Record<'FREE' | 'STANDARD' | 'ENTERPRISE', number | null> = {
  FREE: 100,
  STANDARD: 5000,
  ENTERPRISE: null,
};

export const mockPlanQuotaUsers: PlanQuotaUser[] = [
  {
    id: 1,
    name: '김민준',
    email: 'minjun.kim@company.com',
    companyId: 1,
    companyName: '테크노빌딩관리',
    plan: 'STANDARD',
    quotaUsed: 1450,
    quotaLimit: QUOTA_LIMIT_BY_PLAN.STANDARD,
    remainingDays: 245,
    status: 'ACTIVE',
  },
  {
    id: 2,
    name: '이서연',
    email: 'seoyeon.lee@company.com',
    companyId: 1,
    companyName: '테크노빌딩관리',
    plan: 'STANDARD',
    quotaUsed: 980,
    quotaLimit: QUOTA_LIMIT_BY_PLAN.STANDARD,
    remainingDays: 180,
    status: 'ACTIVE',
  },
  {
    id: 3,
    name: '박도윤',
    email: 'doyoon.park@company.com',
    companyId: 2,
    companyName: '그린타워시설관리',
    plan: 'ENTERPRISE',
    quotaUsed: 120,
    quotaLimit: QUOTA_LIMIT_BY_PLAN.ENTERPRISE,
    remainingDays: 90,
    status: 'ACTIVE',
  },
  {
    id: 4,
    name: '최지우',
    email: 'jiwoo.choi@company.com',
    companyId: 1,
    companyName: '테크노빌딩관리',
    plan: 'STANDARD',
    // 경고 임계(90%) 이상 렌더 확인용 — 공용 한도를 많이 소진한 사용자
    quotaUsed: 4700,
    quotaLimit: QUOTA_LIMIT_BY_PLAN.STANDARD,
    // 만료 임박 상태 확인용
    remainingDays: 12,
    status: 'WARNING',
  },
  {
    id: 5,
    name: '정하은',
    email: 'haeun.jung@company.com',
    companyId: 3,
    companyName: '한빛건설',
    plan: 'FREE',
    quotaUsed: 38,
    quotaLimit: QUOTA_LIMIT_BY_PLAN.FREE,
    remainingDays: 300,
    status: 'ACTIVE',
  },
  {
    id: 6,
    name: '강시우',
    email: 'siwoo.kang@company.com',
    companyId: 3,
    companyName: '한빛건설',
    plan: 'FREE',
    quotaUsed: 64,
    quotaLimit: QUOTA_LIMIT_BY_PLAN.FREE,
    remainingDays: 150,
    status: 'ACTIVE',
  },
  {
    id: 7,
    name: '윤아린',
    email: 'arin.yoon@company.com',
    companyId: 4,
    companyName: '스마트파크FM',
    plan: 'STANDARD',
    quotaUsed: 940,
    quotaLimit: QUOTA_LIMIT_BY_PLAN.STANDARD,
    // 만료된 플랜 확인용
    remainingDays: null,
    status: 'EXPIRED',
  },
  {
    id: 8,
    name: '한서준',
    email: 'seojun.han@company.com',
    // 회사 미소속(개인 계정) — 기업 컬럼·플랜/한도 빈 값(무제한 아님) 표시 확인용
    companyId: null,
    companyName: null,
    plan: null,
    quotaUsed: 0,
    quotaLimit: null,
    remainingDays: null,
    status: 'EXPIRED',
  },
];

// 전체 평균 쿼터 사용률 = 유효 한도를 가진 사용자별 사용률의 평균(회사마다 한도가 다를 수 있어 #508처럼
// 단일 공용 한도로 나눌 수 없다 — 백엔드 PlatformAdminPlanQuotaService#buildStats 와 동일 계산 방식).
function computeAverageQuotaUsagePercent(users: PlanQuotaUser[]): number {
  const percents = users
    .filter((user) => user.quotaLimit !== null && user.quotaLimit > 0)
    .map((user) => Math.min(100, Math.round((user.quotaUsed / (user.quotaLimit ?? 1)) * 100)));
  if (percents.length === 0) {
    return 0;
  }
  return Math.round(percents.reduce((sum, percent) => sum + percent, 0) / percents.length);
}

export const mockPlanQuotaStats: PlanQuotaStats = {
  activeUsers: mockPlanQuotaUsers.filter((user) => user.plan !== null).length,
  totalQuotaUsagePercent: computeAverageQuotaUsagePercent(mockPlanQuotaUsers),
};
