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

// 개업일자 — 국세청 진위확인(#596)이 요구하는 필수값. `<input type="date">`가 넘기는 값은
// 유효하지 않은 날짜면 빈 문자열이 되므로 필수 검증만으로 형식 오류도 함께 걸러지고,
// 추가로 미래 날짜(아직 개업하지 않은 날짜)는 무효 처리한다(#600).
export function isValidBusinessStartDate(value: string): boolean {
  if (!value.trim()) return false;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return false;
  return date.getTime() <= Date.now();
}

// 비밀번호 강도 — 새 비밀번호를 설정하는 화면(기업 회원가입·새 비밀번호 설정)에서 입력값의
// 안전도를 위험/보통/안전 3단계로 안내한다(#414, Figma #32). 순수 클라이언트 판정이며 서버
// 검증을 대체하지 않는다 — 제출 가능 여부는 여전히 isValidPassword가 결정한다.
export type PasswordStrength = 'weak' | 'medium' | 'strong';

const PASSWORD_HAS_SPECIAL = /[^A-Za-z0-9]/;

// 빈 값이면 표시하지 않으므로 null. 그 외에는 규칙 충족 정도로 3단계 산정:
// - weak(위험): 최소 요건(8자+영문+숫자) 미충족
// - medium(보통): 최소 요건은 충족하나 특수문자 없고 12자 미만
// - strong(안전): 최소 요건 충족 + (12자 이상 또는 특수문자 포함)
export function getPasswordStrength(password: string): PasswordStrength | null {
  if (password.length === 0) return null;
  if (!isValidPassword(password)) return 'weak';
  if (password.length >= 12 || PASSWORD_HAS_SPECIAL.test(password)) return 'strong';
  return 'medium';
}
