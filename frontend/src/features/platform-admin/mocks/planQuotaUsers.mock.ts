import type { PlanQuotaStats, PlanQuotaUser } from '../planQuota.types';

// 플랫폼 관리자 플랜·쿼터 관리 예제 데이터 — features/admin/mocks/planQuotaUsers.mock.ts(#508)를
// 그대로 옮긴 것(#625). 백엔드 /api/platform-admin/plans-quota(#624) 확정 전까지 화면 검증용.
// 계정은 전부 합성값(실데이터 아님).

const COMPANY_PLAN = 'STANDARD' as const;
const COMPANY_QUOTA_LIMIT = 5000;

export const mockPlanQuotaUsers: PlanQuotaUser[] = [
  {
    id: 1,
    name: '김민준',
    email: 'minjun.kim@company.com',
    plan: COMPANY_PLAN,
    quotaUsed: 1450,
    quotaLimit: COMPANY_QUOTA_LIMIT,
  },
  {
    id: 2,
    name: '이서연',
    email: 'seoyeon.lee@company.com',
    plan: COMPANY_PLAN,
    quotaUsed: 980,
    quotaLimit: COMPANY_QUOTA_LIMIT,
  },
  {
    id: 3,
    name: '박도윤',
    email: 'doyoon.park@company.com',
    plan: COMPANY_PLAN,
    quotaUsed: 120,
    quotaLimit: COMPANY_QUOTA_LIMIT,
  },
  {
    id: 4,
    name: '최지우',
    email: 'jiwoo.choi@company.com',
    plan: COMPANY_PLAN,
    // 경고 임계(90%) 이상 렌더 확인용 — 공용 한도를 많이 소진한 사용자
    quotaUsed: 4700,
    quotaLimit: COMPANY_QUOTA_LIMIT,
  },
  {
    id: 5,
    name: '정하은',
    email: 'haeun.jung@company.com',
    plan: COMPANY_PLAN,
    quotaUsed: 38,
    quotaLimit: COMPANY_QUOTA_LIMIT,
  },
  {
    id: 6,
    name: '강시우',
    email: 'siwoo.kang@company.com',
    plan: COMPANY_PLAN,
    quotaUsed: 640,
    quotaLimit: COMPANY_QUOTA_LIMIT,
  },
  {
    id: 7,
    name: '윤아린',
    email: 'arin.yoon@company.com',
    plan: COMPANY_PLAN,
    quotaUsed: 940,
    quotaLimit: COMPANY_QUOTA_LIMIT,
  },
  {
    id: 8,
    name: '한서준',
    email: 'seojun.han@company.com',
    // 초대 후 아직 활성화되지 않은 사용자 — 플랜/한도 빈 값(무제한 아님) 표시 확인용
    plan: null,
    quotaUsed: 0,
    quotaLimit: null,
  },
];

// 전체 쿼터 사용률 = 유효 한도를 가진 사용자들의 총 사용량 / 공용 한도.
// 무제한·미구독 사용자는 분모에서 제외한다.
function computeTotalQuotaUsagePercent(users: PlanQuotaUser[]): number {
  const bounded = users.filter((user) => user.quotaLimit !== null && user.quotaLimit > 0);
  const totalUsed = bounded.reduce((sum, user) => sum + user.quotaUsed, 0);
  const totalLimit = bounded.length > 0 ? (bounded[0].quotaLimit ?? 0) : 0;
  if (totalLimit <= 0) {
    return 0;
  }
  return Math.min(100, Math.round((totalUsed / totalLimit) * 100));
}

export const mockPlanQuotaStats: PlanQuotaStats = {
  activeUsers: mockPlanQuotaUsers.filter((user) => user.plan !== null).length,
  totalQuotaUsagePercent: computeTotalQuotaUsagePercent(mockPlanQuotaUsers),
  companyPlan: COMPANY_PLAN,
};
