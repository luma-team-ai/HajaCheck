// 새 비밀번호 설정 폼 검증 — #301(HAJA-224), 계약: newPassword는 가입과 동일 정책(isValidPassword 재사용)
import { doPasswordsMatch, isValidPassword } from './authFormValidators';

export function isResetPasswordFormValid(newPassword: string, confirmPassword: string): boolean {
  return isValidPassword(newPassword) && doPasswordsMatch(newPassword, confirmPassword);
}
