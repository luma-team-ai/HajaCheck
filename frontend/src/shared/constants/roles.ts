// 사용자 권한 역할 — 백엔드 DDL role_type(ADMIN/INSPECTOR/USER/COUNSELOR)과 값이 정확히 일치한다.
// (docs/design/db/HajaCheck_script.sql / backend auth.entity.Role)
//
// shared에 두는 이유: features/auth(로그인 사용자)와 features/admin(사용자 관리 목록)이 같은 값을
// 쓰고, shared/components/AdminRoute도 이 타입으로 가드한다. feature마다 로컬 정의하면 백엔드에
// 값이 추가될 때 한 곳만 고치고 넘어가기 쉽고, 그러면 타입 검사는 통과하는데 라벨 Record 조회가
// undefined가 되어 배지가 빈칸으로 렌더된다(React_코드_컨벤션.md §1 "공유가 필요해지면 shared/로 승격").
//
// PLATFORM_ADMIN(#535, PRD v0.47) — company_id가 없는 플랫폼 운영진 전용 축. 기존 ADMIN(기업
// 관리자, company_id 스코프)과는 별개 계층이라 isAdminRole과 별도 판정 함수(isPlatformAdminRole)를 둔다.
export type Role = 'ADMIN' | 'INSPECTOR' | 'USER' | 'COUNSELOR' | 'PLATFORM_ADMIN';

// "이 사용자가 관리자인가" 판정을 한 곳에 둔다 — AppShellRoute(사이드바 관리자 메뉴 노출)와
// AdminRoute(실제 접근 차단)가 각자 role === 'ADMIN'을 따로 비교하면, 역할 체계가 바뀔 때
// 한쪽만 고치고 넘어가는 실수로 "메뉴는 보이는데 클릭하면 튕기는" 화면이 생긴다(#378 리뷰 지적).
export function isAdminRole(role: Role | undefined): boolean {
  return role === 'ADMIN';
}

// PLATFORM_ADMIN 전용 판정(#535) — PlatformAdminShellRoute(nav 노출)와 PlatformAdminRoute(접근
// 차단)가 같은 기준을 쓰도록 isAdminRole과 동일한 이유로 분리한다.
export function isPlatformAdminRole(role: Role | undefined): boolean {
  return role === 'PLATFORM_ADMIN';
}

// COUNSELOR 전용 판정(#1001, HAJA-495) — 상담원 콘솔(CounselorShellRoute/CounselorRoute)이 같은
// 기준을 쓰도록 isAdminRole/isPlatformAdminRole과 동일한 이유로 분리한다. COUNSELOR는 ADMIN도
// PLATFORM_ADMIN도 아닌 별개 축이라 두 판정 함수를 재사용하지 않고 전용 함수를 둔다.
export function isCounselorRole(role: Role | undefined): boolean {
  return role === 'COUNSELOR';
}

// 기업회원 대시보드(AppShell) 이용 role 집합(#1513) — company_id 스코프 안에서 일하는 "admin 이하"
// 세 role이다. PLATFORM_ADMIN(플랫폼 운영 축)·COUNSELOR(상담 축)는 이 셸에 볼일이 없고 각자 전용
// 콘솔을 쓴다.
//
// 상수와 판정 함수를 같이 두는 이유: 기업 로그인 훅(features/auth/useLogin)은 "허용 role인가"를
// 함수로 묻고, 라우터(app/router.tsx)는 같은 집합을 ProtectedRoute.allowedRoles 튜플로 넘긴다.
// 두 곳이 role 배열을 각자 인라인으로 적으면 역할 체계가 바뀔 때 한쪽만 고치는 실수가 나온다
// (isAdminRole 주석의 #378 지적과 동일 이유 — 로그인은 막았는데 라우트는 뚫려 있는 상태가 된다).
// readonly 튜플: allowedRoles와 같은 "비어 있지 않은" 형태를 유지하면서 외부 변조를 막는다.
export const COMPANY_DASHBOARD_ROLES: readonly [Role, ...Role[]] = ['ADMIN', 'INSPECTOR', 'USER'];

export function isCompanyDashboardRole(role: Role | undefined): boolean {
  return role !== undefined && COMPANY_DASHBOARD_ROLES.includes(role);
}
