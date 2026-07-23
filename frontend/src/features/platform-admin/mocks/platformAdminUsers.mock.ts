import type { AdminUser, AdminUserStats } from '../types';

// 사용자 관리 예제 데이터 — Figma node-id 177-2017 표에 나온 행을 그대로 옮기고, 페이지네이션 동작을
// 확인할 수 있도록 24건으로 늘렸다. 계정은 전부 example.com 합성값(실데이터 아님).
//
// 최근 접속은 "2시간 전"처럼 상대 표기라 고정 ISO 문자열을 한 번만 구워두면(모듈 로드 시각 기준) 개발
// 서버 탭을 오래 열어둘수록 실제 렌더 시점(formatRelativeAccess가 매번 참조하는 실시간 Date.now())과
// 어긋난다(#398). "지금으로부터 n만큼 전"이라는 오프셋만 값으로 갖고, lastAccessAt은 매 접근 시점의
// Date.now() 기준으로 계산되는 getter로 둬 언제 읽어도 "2시간 전"이 그대로 유지되게 한다.
const HOUR = 60 * 60 * 1000;
const DAY = 24 * HOUR;

type MockAdminUserSeed = Omit<AdminUser, 'lastAccessAt'> & { lastAccessOffsetMs: number | null };

function toAdminUser({ lastAccessOffsetMs, ...rest }: MockAdminUserSeed): AdminUser {
  const user = rest as AdminUser;
  Object.defineProperty(user, 'lastAccessAt', {
    enumerable: true,
    get(): string | null {
      return lastAccessOffsetMs === null ? null : new Date(Date.now() - lastAccessOffsetMs).toISOString();
    },
  });
  return user;
}

const mockAdminUserSeeds: MockAdminUserSeed[] = [
  {
    id: 1,
    name: '김지수',
    email: 'jisoo.kim@example.com',
    role: 'USER',
    plan: 'FREE',
    joinedAt: '2023-10-12',
    lastAccessOffsetMs: 2 * HOUR,
    status: 'ACTIVE',
  },
  {
    id: 2,
    name: '박진우',
    email: 'jinwoo.park@example.com',
    role: 'ADMIN',
    plan: 'ENTERPRISE',
    joinedAt: '2022-01-05',
    lastAccessOffsetMs: 0.2 * HOUR,
    status: 'ACTIVE',
  },
  {
    id: 3,
    name: '이하늘',
    email: 'haneul.lee@example.com',
    role: 'INSPECTOR',
    plan: 'STANDARD',
    joinedAt: '2023-05-20',
    lastAccessOffsetMs: 31 * DAY,
    status: 'SUSPENDED',
  },
  {
    id: 4,
    name: '최민수',
    email: 'minsu.choi@example.com',
    role: 'COUNSELOR',
    plan: 'STANDARD',
    joinedAt: '2023-11-01',
    lastAccessOffsetMs: 1 * DAY,
    status: 'ACTIVE',
  },
  {
    id: 5,
    name: '배시온',
    email: 'contractor_01@partner.com',
    role: 'INSPECTOR',
    // 활성 구독(user_plans) 행이 없는 사용자 — 플랜 셀 빈 값 표시 확인용
    plan: null,
    joinedAt: '2024-05-09',
    lastAccessOffsetMs: 3 * DAY,
    status: 'ACTIVE',
  },
  {
    id: 6,
    name: '강서연',
    email: 'seoyeon.kang@example.com',
    role: 'USER',
    plan: 'FREE',
    joinedAt: '2023-12-05',
    lastAccessOffsetMs: 3 * HOUR,
    status: 'ACTIVE',
  },
  {
    id: 7,
    name: '윤도현',
    email: 'dohyun.yoon@example.com',
    role: 'ADMIN',
    plan: 'ENTERPRISE',
    joinedAt: '2021-08-15',
    lastAccessOffsetMs: 1 * DAY,
    status: 'ACTIVE',
  },
  {
    id: 8,
    name: '임지영',
    email: 'jiyoung.lim@example.com',
    role: 'INSPECTOR',
    plan: 'STANDARD',
    joinedAt: '2023-09-22',
    lastAccessOffsetMs: 5 * DAY,
    status: 'ACTIVE',
  },
  {
    id: 9,
    name: '오재원',
    email: 'jaewon.oh@example.com',
    role: 'COUNSELOR',
    plan: 'STANDARD',
    joinedAt: '2023-08-11',
    lastAccessOffsetMs: 8 * DAY,
    status: 'ACTIVE',
  },
  {
    id: 10,
    name: '장수진',
    email: 'sujin.jang@example.com',
    role: 'USER',
    plan: 'FREE',
    joinedAt: '2024-01-10',
    lastAccessOffsetMs: 1 * DAY,
    status: 'ACTIVE',
  },
  {
    id: 11,
    name: '한예린',
    email: 'yerin.han@example.com',
    role: 'USER',
    plan: 'STANDARD',
    joinedAt: '2024-02-18',
    lastAccessOffsetMs: 6 * HOUR,
    status: 'ACTIVE',
  },
  {
    id: 12,
    name: '서준호',
    email: 'junho.seo@example.com',
    role: 'INSPECTOR',
    plan: 'ENTERPRISE',
    joinedAt: '2022-06-30',
    lastAccessOffsetMs: 3 * DAY,
    status: 'ACTIVE',
  },
  {
    id: 13,
    name: '노아름',
    email: 'areum.no@example.com',
    role: 'COUNSELOR',
    plan: 'FREE',
    joinedAt: '2024-03-02',
    lastAccessOffsetMs: 14 * DAY,
    status: 'SUSPENDED',
  },
  {
    id: 14,
    name: '서도윤',
    email: 'contractor_02@partner.com',
    role: 'INSPECTOR',
    plan: 'FREE',
    joinedAt: '2024-05-21',
    // 가입 후 한 번도 로그인하지 않은 계정(last_login_at IS NULL) — 최근 접속 빈 값 확인용
    lastAccessOffsetMs: null,
    status: 'ACTIVE',
  },
  {
    id: 15,
    name: '백승우',
    email: 'seungwoo.baek@example.com',
    role: 'USER',
    plan: 'STANDARD',
    joinedAt: '2023-04-14',
    lastAccessOffsetMs: 9 * HOUR,
    status: 'ACTIVE',
  },
  {
    id: 16,
    name: '문가영',
    email: 'gayoung.moon@example.com',
    role: 'ADMIN',
    plan: 'ENTERPRISE',
    joinedAt: '2021-11-23',
    lastAccessOffsetMs: 1 * HOUR,
    status: 'ACTIVE',
  },
  {
    id: 17,
    name: '조태현',
    email: 'taehyun.jo@example.com',
    role: 'INSPECTOR',
    plan: 'STANDARD',
    joinedAt: '2023-07-08',
    lastAccessOffsetMs: 21 * DAY,
    status: 'ACTIVE',
  },
  {
    id: 18,
    name: '신유진',
    email: 'yujin.shin@example.com',
    role: 'USER',
    plan: 'FREE',
    joinedAt: '2024-04-01',
    lastAccessOffsetMs: 2 * DAY,
    status: 'ACTIVE',
  },
  {
    id: 19,
    name: '황민재',
    email: 'minjae.hwang@example.com',
    role: 'COUNSELOR',
    plan: 'STANDARD',
    joinedAt: '2023-02-27',
    lastAccessOffsetMs: 45 * DAY,
    status: 'SUSPENDED',
  },
  {
    id: 20,
    name: '권소라',
    email: 'sora.kwon@example.com',
    role: 'USER',
    plan: 'FREE',
    joinedAt: '2024-05-19',
    lastAccessOffsetMs: 4 * HOUR,
    status: 'ACTIVE',
  },
  {
    id: 21,
    name: '정하람',
    email: 'haram.jung@example.com',
    role: 'INSPECTOR',
    plan: 'ENTERPRISE',
    joinedAt: '2022-09-09',
    lastAccessOffsetMs: 6 * DAY,
    status: 'ACTIVE',
  },
  {
    id: 22,
    name: '차예은',
    email: 'contractor_03@partner.com',
    role: 'COUNSELOR',
    plan: null,
    joinedAt: '2024-06-03',
    lastAccessOffsetMs: 19 * DAY,
    status: 'ACTIVE',
  },
  {
    id: 23,
    name: '유선호',
    email: 'seonho.yoo@example.com',
    role: 'USER',
    plan: 'STANDARD',
    joinedAt: '2023-01-16',
    lastAccessOffsetMs: 10 * DAY,
    status: 'ACTIVE',
  },
  {
    id: 24,
    name: '남지호',
    email: 'jiho.nam@example.com',
    role: 'ADMIN',
    plan: 'ENTERPRISE',
    joinedAt: '2022-03-25',
    lastAccessOffsetMs: 12 * HOUR,
    status: 'ACTIVE',
  },
];

export const mockPlatformAdminUsers: AdminUser[] = mockAdminUserSeeds.map(toAdminUser);

// 통계는 목 배열에서 파생시켜 표(24건)와 카드 숫자가 어긋나지 않게 한다 — Figma의 1,284명은
// 시안용 더미 수치이므로 그대로 박지 않는다. 실제 값은 백엔드 집계로 대체된다.
export const mockPlatformAdminUserStats: AdminUserStats = {
  totalMembers: mockPlatformAdminUsers.length,
  active: mockPlatformAdminUsers.filter((user) => user.status === 'ACTIVE').length,
  suspended: mockPlatformAdminUsers.filter((user) => user.status === 'SUSPENDED').length,
  newThisWeek: 3,
  newThisWeekGrowthRate: 12,
};
