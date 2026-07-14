// 인증 관련 공통 경로 — shared/api 인터셉터와 features/auth 양쪽에서 참조(하드코딩 중복 방지)
// basename 배포(예: vite base='/app/')에서도 실제 서비스되는 로그인 경로와 정확히 일치해야
// 가드 비교(pathname === LOGIN_PATH)와 리다이렉트 대상(window.location.href = LOGIN_PATH)이
// 어긋나지 않는다 — import.meta.env.BASE_URL(vite base, 기본 '/', 항상 '/'로 끝남)을 반영해 계산.
function normalizePath(path: string): string {
  return path.replace(/\/{2,}/g, '/');
}

export const LOGIN_PATH = normalizePath(`${import.meta.env.BASE_URL}login`);
