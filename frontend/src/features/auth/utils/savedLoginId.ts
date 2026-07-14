// "아이디 저장" — 비밀번호·토큰은 저장하지 않음(로그인 아이디만)
const SAVED_LOGIN_ID_KEY = 'hajacheckSavedLoginId';

export function getSavedLoginId(): string | null {
  return localStorage.getItem(SAVED_LOGIN_ID_KEY);
}

export function setSavedLoginId(loginId: string): void {
  localStorage.setItem(SAVED_LOGIN_ID_KEY, loginId);
}

export function clearSavedLoginId(): void {
  localStorage.removeItem(SAVED_LOGIN_ID_KEY);
}
