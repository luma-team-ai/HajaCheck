// 새 비밀번호 설정 폼 검증 — HAJA-170(#187)
import { doPasswordsMatch, isValidPassword } from './authFormValidators';

export function isResetPasswordFormValid(newPassword: string, confirmPassword: string): boolean {
  return isValidPassword(newPassword) && doPasswordsMatch(newPassword, confirmPassword);
}
