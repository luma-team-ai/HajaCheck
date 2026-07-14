// 로그인 화면 소셜 로그인 리다이렉트 경로 — URL 하드코딩 방지(React_코드_컨벤션.md §8)
export const KAKAO_OAUTH_PATH = '/api/auth/oauth2/kakao';
export const GOOGLE_OAUTH_PATH = '/api/auth/oauth2/google';

// 기업 인증 플로우 — HAJA-170(#187) — API 경로(axios baseURL='/api' 기준 상대경로)
export const EMAIL_AVAILABILITY_PATH = '/auth/email-availability';
export const COMPANY_SIGNUP_PATH = '/auth/companies';
export const COMPANY_SIGNUP_STATUS_PATH = '/auth/companies/status';
export const ID_INQUIRY_PATH = '/auth/id-inquiry';
export const PASSWORD_INQUIRY_PATH = '/auth/password-inquiry';
export const PASSWORD_RESET_PATH = '/auth/password-reset';

// 기업 인증 플로우 — 라우트 경로(app/router.tsx와 공유, 하드코딩 방지)
export const COMPANY_SIGNUP_ROUTE = '/signup/company';
export const COMPANY_SIGNUP_PENDING_ROUTE = '/signup/company/pending';
export const FIND_ID_ROUTE = '/find-id';
export const FIND_PASSWORD_ROUTE = '/find-password';
export const RESET_PASSWORD_ROUTE = '/reset-password';

// 사업자등록증 업로드 제약 — 계약(contract.md) FILE_INVALID_TYPE/FILE_TOO_LARGE와 정합
export const BUSINESS_LICENSE_ALLOWED_TYPES = ['image/jpeg', 'image/png', 'application/pdf'];
export const BUSINESS_LICENSE_MAX_SIZE_BYTES = 10 * 1024 * 1024;
