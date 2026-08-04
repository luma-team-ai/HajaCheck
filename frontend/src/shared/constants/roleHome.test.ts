// role 집합(COMPANY_DASHBOARD_ROLES)과 role 홈(resolveRoleHomeRoute)의 커플링을 코드로 고정한다(#1513).
//
// 왜 별도 파일인가: 다른 테스트들의 단언은 전부 COMPANY_DASHBOARD_ROLES 자신에서 파생돼 있어
// (router.appShellRoleGate.test.tsx는 상수끼리 비교, ProtectedRoute.test.tsx는 그 상수로 라우트를
// 구성) 상수에서 'INSPECTOR'/'USER'를 **지우면 전건이 통과한 채** 해당 사용자가 기업 셸 전체에서
// 튕겨난다(추가는 기존 테스트가 잡지만 제거는 무방비였다). 여기서 값 자체와 전수 불변식을 못박는다.
import { describe, expect, it } from 'vitest';
import {
  COMPANY_DASHBOARD_ROLES,
  isCounselorRole,
  isPlatformAdminRole,
  type Role,
} from './roles';
import {
  COUNSELOR_QUEUE_ROUTE,
  DASHBOARD_ROUTE,
  PLATFORM_ADMIN_ROUTE,
  resolveRoleHomeRoute,
} from './routes';

// Record<Role, true>라 Role 유니온에 값이 추가되면 이 객체가 **컴파일 에러**가 난다 —
// 새 role이 아래 전수 단언을 그냥 비껴가는 일이 없도록(resolveRoleHomeRoute의 never 체크와 한 쌍).
const ALL_ROLES_PRESENT: Record<Role, true> = {
  ADMIN: true,
  INSPECTOR: true,
  USER: true,
  COUNSELOR: true,
  PLATFORM_ADMIN: true,
};
const ALL_ROLES = Object.keys(ALL_ROLES_PRESENT) as Role[];

describe('COMPANY_DASHBOARD_ROLES', () => {
  // 값 고정 — 상수에서 role을 지우는 변경은 이 단언이 유일하게 잡는다(다른 테스트는 전부 파생).
  it('기업 대시보드(AppShell) 허용 role은 ADMIN·INSPECTOR·USER 세 개다', () => {
    expect(COMPANY_DASHBOARD_ROLES).toEqual(['ADMIN', 'INSPECTOR', 'USER']);
  });

  // 백엔드 COMPANY_PORTAL_ROLES(AuthController)와 같은 집합이어야 한다 — 서버가 로그인시켜 준
  // role을 프론트 셸이 튕겨내거나(로그인은 되는데 화면이 안 뜸), 반대로 서버가 막는 role에게
  // 화면만 열어주는(빈 화면·403 도배) 어긋남을 막는다.
  it('플랫폼 운영 축(PLATFORM_ADMIN)·상담 축(COUNSELOR)은 포함하지 않는다', () => {
    expect(COMPANY_DASHBOARD_ROLES).not.toContain('PLATFORM_ADMIN');
    expect(COMPANY_DASHBOARD_ROLES).not.toContain('COUNSELOR');
  });
});

describe('resolveRoleHomeRoute 불변식', () => {
  // 핵심 불변식: 어떤 role이든 그 홈은 "그 role이 통과할 수 있는 가드 뒤"여야 한다.
  // 어기면 거부 → 홈 → 거부로 되돌아 백지 데드엔드가 된다(#1513 security-review 지적).
  it.each(ALL_ROLES)('%s의 홈은 그 role이 통과할 수 있는 화면이다', (role) => {
    const home = resolveRoleHomeRoute(role);

    if (isPlatformAdminRole(role)) {
      // PlatformAdminRoute가 통과시키는 화면
      expect(home).toBe(PLATFORM_ADMIN_ROUTE);
      return;
    }
    if (isCounselorRole(role)) {
      // CounselorRoute가 통과시키는 화면
      expect(home).toBe(COUNSELOR_QUEUE_ROUTE);
      return;
    }
    // 나머지는 기업 셸로 폴백한다 — 그렇다면 반드시 그 셸의 allowedRoles에 들어 있어야 한다.
    expect(home).toBe(DASHBOARD_ROUTE);
    expect(COMPANY_DASHBOARD_ROLES).toContain(role);
  });

  // 반대 방향도 고정한다 — 기업 셸 허용 role의 홈이 대시보드가 아니면, 로그인 직후·거부 직후에
  // 자기 홈이 아닌 콘솔로 튕겨 다시 거부당한다.
  it.each(COMPANY_DASHBOARD_ROLES)('%s(기업 셸 허용 role)의 홈은 대시보드다', (role) => {
    expect(resolveRoleHomeRoute(role)).toBe(DASHBOARD_ROUTE);
  });

  // role 미상(가드 진입 전·세션 복원 실패)은 기본 화면으로 — 여기서 예외를 던지면 가드가 깨진다.
  it('role이 undefined면 기본 화면(대시보드)을 돌려준다', () => {
    expect(resolveRoleHomeRoute(undefined)).toBe(DASHBOARD_ROUTE);
  });
});
