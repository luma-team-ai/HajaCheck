// 기업회원 로그인 폼 검증 — 빈 값(공백만 있는 값 포함) 처리
export function isLoginFormValid(loginId: string, password: string): boolean {
  return loginId.trim().length > 0 && password.trim().length > 0;
}
