// 인증 관련 공통 경로 — shared/api 인터셉터와 features/auth 양쪽에서 참조(하드코딩 중복 방지)
// basename 배포(예: vite base='/app/')에서도 실제 서비스되는 로그인 경로와 정확히 일치해야
// 가드 비교(pathname === LOGIN_PATH)와 리다이렉트 대상(window.location.href = LOGIN_PATH)이
// 어긋나지 않는다 — import.meta.env.BASE_URL(vite base, 기본 '/', 항상 '/'로 끝남)을 반영해 계산.
function normalizePath(path: string): string {
  return path.replace(/\/{2,}/g, '/');
}

export const LOGIN_PATH = normalizePath(`${import.meta.env.BASE_URL}login`);

// 플랫폼 관리자 콘솔(#535) 전용 401 리다이렉트 대상 — axios.ts 인터셉터가 pathname이
// PLATFORM_ADMIN_PATH_PREFIX로 시작하면 LOGIN_PATH 대신 이 값으로 리다이렉트한다.
export const PLATFORM_ADMIN_LOGIN_PATH = normalizePath(`${import.meta.env.BASE_URL}platform-admin/login`);

// axios.ts 인터셉터가 "이 요청이 플랫폼 관리자 콘솔 경로에서 발생했는가"를 판별하는 접두사.
// basename 배포(예: vite base='/app/')에서 BASE_URL을 반영하지 않고 raw '/platform-admin'으로만
// 비교하면, 실제 pathname은 '/app/platform-admin/...'이라 startsWith가 항상 false가 되어 401 시
// 기업회원 로그인(LOGIN_PATH)으로 잘못 리다이렉트된다(PR머신 리뷰 P2, #558).
export const PLATFORM_ADMIN_PATH_PREFIX = normalizePath(`${import.meta.env.BASE_URL}platform-admin`);
