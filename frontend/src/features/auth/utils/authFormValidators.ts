// 기업 인증 플로우 공통 검증기 — HAJA-170(#187), 여러 화면(회원가입/아이디 찾기)에서 재사용
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

// getPasswordStrength(비밀번호 강도미터)는 과거 새 비밀번호 설정 화면 전용이었으나 그 화면이
// 계정 탈취 P1로 배포에서 빠지면서 함께 제거됐다. 화면은 이메일 링크 방식으로 되살아났지만
// (ResetPasswordPage, #301/HAJA-224) 강도미터는 시안·제품 결정이 없어 복원하지 않았다.
// 필요해지면 별도 이슈로 다룬다 — 지금 없는 것은 의도된 상태다.
