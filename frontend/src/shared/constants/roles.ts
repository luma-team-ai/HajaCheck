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
