import type { PlanQuotaStats, PlanQuotaUser } from '../planQuota.types';

// 플랜·쿼터 관리 예제 데이터 — Figma node-id 1197-3519 표 레이아웃(행 수·페이지네이션 동작)만 참고하고,
// 시안의 "Hyundai Motors/TechCorp Inc." 같은 타사 이름은 쓰지 않는다 — 실제 스코프는 로그인한
// 관리자 소속 "내 회사" 하나이므로, 아래 행은 전부 같은 회사(STANDARD 플랜, 월 한도 5,000장)에
// 속한 개인 멤버들이다. 계정은 전부 합성값(실데이터 아님).

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
    // 경고 임계(90%) 이상 렌더 확인용 — 공용 한도를 많이 소진한 멤버
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
    // 초대 후 아직 활성화되지 않은 멤버 — 플랜/한도 빈 값(무제한 아님) 표시 확인용
    plan: null,
    quotaUsed: 0,
    quotaLimit: null,
  },
];

// 전체 쿼터 사용률 = 유효 한도를 가진 멤버들의 총 사용량 / 공용 한도(회사 플랜 기준, 멤버 수와 무관하게 동일).
// 무제한·미구독 멤버는 분모에서 제외한다.
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
  // 로그인 관리자 소속 회사의 플랜 — 멤버 행과 무관한 고정값
  companyPlan: COMPANY_PLAN,
};
