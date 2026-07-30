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

// 초대 코드 입력(#799, #794) — 소셜 최초 로그인 직후 status=WAITING(company_id 없음)인 사용자가
// 발급받은 초대 코드로 회사에 연결하는 화면. ProtectedRoute가 WAITING 사용자를 여기로 강제
// 리다이렉트해야 하므로(보호 라우트 어디로 가든 동일하게 가로채야 함) shared 상수로 둔다.
export const INVITE_CODE_ROUTE = '/invite-code';

// 토스페이먼츠 결제창 연동(#989, HAJA-490) — 결제창(외부 페이지)에서 돌아오는 successUrl/failUrl
// 리다이렉트 대상. features/mypage의 useRequestTossPayment 훅이 절대 URL을 조립할 때도 이 값을
// 공유한다(하드코딩 금지 방지 — router.tsx와 동일 문자열을 두 곳에서 따로 관리하면 드리프트 위험).
export const PAYMENT_SUCCESS_ROUTE = '/payments/success';
export const PAYMENT_FAIL_ROUTE = '/payments/fail';

// 상담원 콘솔(#1001, HAJA-495) — COUNSELOR 전용 대기열 화면. 로그인 후 role=COUNSELOR 리다이렉트
// (useCounselorLogin.ts/CounselorLoginPage.tsx)와 CounselorRoute 권한 부족 리다이렉트가 같은 값을
// 참조하도록 PLATFORM_ADMIN_ROUTE와 동일한 이유로 shared 상수로 둔다.
export const COUNSELOR_QUEUE_ROUTE = '/counsel-console/queue';

// 상담원 전용 로그인(플랫폼 관리자 로그인과 동일 디자인, 라벨만 "상담원 로그인") — 기업회원 로그인
// (LOGIN_ROUTE)과 분리된 전용 경로. CounselorRoute가 미인증 시 이 값으로 리다이렉트한다.
export const COUNSELOR_LOGIN_ROUTE = '/counsel-console/login';
