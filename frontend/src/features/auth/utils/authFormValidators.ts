// 기업 인증 플로우 공통 검증기 — HAJA-170(#187), 여러 화면(회원가입/아이디·비밀번호 찾기)에서 재사용
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
// 계약(contract.md): @Size(min=8), 영문+숫자 포함
const PASSWORD_HAS_LETTER = /[A-Za-z]/;
const PASSWORD_HAS_DIGIT = /\d/;

export function isValidEmail(email: string): boolean {
  return EMAIL_PATTERN.test(email.trim());
}

export function isValidPassword(password: string): boolean {
  return (
    password.length >= 8 && PASSWORD_HAS_LETTER.test(password) && PASSWORD_HAS_DIGIT.test(password)
  );
}

export function doPasswordsMatch(password: string, confirmPassword: string): boolean {
  return password.length > 0 && password === confirmPassword;
}

// 사업자등록번호 — 하이픈 포함/미포함 모두 입력 허용, 정규화 후 10자리 숫자인지 검증
export function normalizeBusinessNumber(value: string): string {
  return value.replace(/\D/g, '');
}

export function isValidBusinessNumber(value: string): boolean {
  return normalizeBusinessNumber(value).length === 10;
}

export type PasswordStrength = 'weak' | 'medium' | 'strong';

// 새 비밀번호 설정 화면의 강도미터용 — 유효성 검증과는 별개(참고용 시각 피드백)
export function getPasswordStrength(password: string): PasswordStrength {
  if (!isValidPassword(password)) return 'weak';

  const hasSpecial = /[^A-Za-z0-9]/.test(password);
  if (password.length >= 12 && hasSpecial) return 'strong';
  return 'medium';
}
