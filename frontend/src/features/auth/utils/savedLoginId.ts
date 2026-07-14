// "아이디 저장" — 비밀번호·토큰은 저장하지 않음(로그인 아이디만)
// 프라이빗 모드·스토리지 차단 등으로 localStorage 접근이 막힌 환경에서도 로그인 폼이
// 크래시하지 않도록 접근 실패는 조용히 무시한다(저장 실패해도 로그인 기능 자체엔 영향 없음).
const SAVED_LOGIN_ID_KEY = 'hajacheckSavedLoginId';

export function getSavedLoginId(): string | null {
  try {
    return localStorage.getItem(SAVED_LOGIN_ID_KEY);
  } catch {
    return null;
  }
}

export function setSavedLoginId(loginId: string): void {
  try {
    localStorage.setItem(SAVED_LOGIN_ID_KEY, loginId);
  } catch {
    // 저장 실패 무시
  }
}

export function clearSavedLoginId(): void {
  try {
    localStorage.removeItem(SAVED_LOGIN_ID_KEY);
  } catch {
    // 삭제 실패 무시
  }
}

// "아이디 저장" 체크 토글 시 저장할 값을 결정 — 체크했더라도 빈 값(공백만 포함)이면 저장하지 않는다(null=제거 대상)
export function resolveSavedLoginId(checked: boolean, loginId: string): string | null {
  const trimmed = loginId.trim();
  return checked && trimmed ? trimmed : null;
}
