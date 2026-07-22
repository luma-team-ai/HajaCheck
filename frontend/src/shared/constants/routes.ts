// shared/components/ProtectedRoute가 리다이렉트 대상으로 쓰는 라우트 경로.
// 두 상수를 한곳에 모아둔다 — 같은 가드에서 한쪽은 features/auth, 한쪽은 shared를 참조하면
// 세 번째 대상을 추가할 때 어디에 둘지 기준이 사라진다.
// features/auth/constants.ts는 이 값을 재export해 auth 플로우 내부 사용처(7곳)의 import 경로를 유지한다.
//
// 주의: 서비스되는 로그인 경로 문자열은 shared/constants/authPaths.ts의 LOGIN_PATH에도 있다.
// 그쪽은 axios 401 인터셉터가 window.location으로 쓰는 값이라 vite base(BASE_URL)가 반영된 절대경로고,
// 여기 LOGIN_ROUTE는 react-router 내부 경로(basename이 라우터에서 따로 붙는다)라 형태가 다르다.
// basename 배포 설정을 바꾼다면 두 파일을 함께 확인할 것.

/** 미인증 시 리다이렉트 대상(react-router 경로) */
export const LOGIN_ROUTE = '/login';

/** 인증은 됐으나 권한이 없을 때(AdminRoute·ProtectedRoute allowedRoles 불충족) 되돌려보낼 기본 화면 */
export const DASHBOARD_ROUTE = '/dashboard';

/** 랜딩(홈) — 로그인 화면 로고 클릭 등 공개 진입점(#421). router.tsx 루트 경로와 일치 */
export const LANDING_ROUTE = '/';

// 플랫폼 관리자 콘솔(#535) — 기업회원 로그인(LOGIN_ROUTE)과 분리된 전용 로그인 경로.
// PlatformAdminRoute가 미인증 시 이 값으로 리다이렉트한다.
export const PLATFORM_ADMIN_LOGIN_ROUTE = '/platform-admin/login';

/** 플랫폼 관리자 콘솔 진입점 — 로그인 성공 후 이동 대상(router.tsx에서 첫 메뉴로 재리다이렉트) */
export const PLATFORM_ADMIN_ROUTE = '/platform-admin';
