// 로그인 화면 소셜 로그인 리다이렉트 경로 — URL 하드코딩 방지(React_코드_컨벤션.md §8)
export const KAKAO_OAUTH_PATH = '/api/auth/oauth2/kakao';
export const GOOGLE_OAUTH_PATH = '/api/auth/oauth2/google';

// 기업 인증 플로우 — HAJA-170(#187) — API 경로(axios baseURL='/api' 기준 상대경로)
export const EMAIL_AVAILABILITY_PATH = '/auth/email-availability';
export const COMPANY_SIGNUP_PATH = '/auth/companies';
export const COMPANY_SIGNUP_STATUS_PATH = '/auth/companies/status';
export const ID_INQUIRY_PATH = '/auth/id-inquiry';
// 비밀번호 찾기 — 이메일 링크 방식(#301, HAJA-224) — docs/api-contract/contract.md "비밀번호 찾기 1·2단계"
export const PASSWORD_RESET_REQUEST_PATH = '/auth/password-reset-request';
export const PASSWORD_RESET_PATH = '/auth/password-reset';

// 세션 확인(getMe) 공유 쿼리 — AuthGate(app/AuthGate.tsx)와 LoginPage가 동일 키/옵션을 사용해야
// react-query 캐시를 공유한다(중복 호출 방지). staleTime은 로그아웃 직후(useLogout이
// setQueryData(AUTH_ME_QUERY_KEY, null)로 캐시를 settled-null 고정) /login으로 전환될 때,
// 방금 고정한 값이 곧바로 stale 취급돼 LoginPage 마운트가 getMe를 즉시 재요청 →
// 쿠키가 아직 유효하면(로그아웃 API 실패 등) 세션이 재복원되는 것을 막기 위함(PR #232 P2-D).
// 하드 새로고침은 캐시 자체가 없는 새 QueryClient라 staleTime과 무관하게 정상적으로 fetch된다.
export const AUTH_ME_QUERY_KEY = ['auth', 'me'] as const;
export const AUTH_ME_QUERY_STALE_TIME_MS = 5000;

// 기업 인증 플로우 — 라우트 경로(app/router.tsx와 공유, 하드코딩 방지)
export const LOGIN_ROUTE = '/login';
export const COMPANY_SIGNUP_ROUTE = '/signup/company';
export const COMPANY_SIGNUP_PENDING_ROUTE = '/signup/company/pending';
export const FIND_ID_ROUTE = '/find-id';
// 비밀번호 찾기 — 이메일 링크 방식(#301, HAJA-224) — RESET_PASSWORD_ROUTE는 메일 링크(계약
// "{FRONTEND_BASE_URL}/reset-password?token=...")와 경로가 반드시 일치해야 한다(하드코딩 금지 방지).
export const FIND_PASSWORD_ROUTE = '/find-password';
export const RESET_PASSWORD_ROUTE = '/reset-password';
// 프로필 클릭 이동 경로(app/AppShellRoute.tsx와 공유, 하드코딩 방지 — #280 P3)
export const MYPAGE_PLAN_ROUTE = '/mypage/plan';

// 사업자등록증 업로드 제약 — 계약(contract.md) FILE_INVALID_TYPE/FILE_TOO_LARGE와 정합
export const BUSINESS_LICENSE_ALLOWED_TYPES = ['image/jpeg', 'image/png', 'application/pdf'];
export const BUSINESS_LICENSE_MAX_SIZE_BYTES = 10 * 1024 * 1024;
