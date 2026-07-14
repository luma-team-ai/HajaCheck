// 비밀번호 찾기 1단계(기업정보 인증) 폼 검증 — HAJA-170(#187)
import { isValidBusinessNumber, isValidEmail } from './authFormValidators';

export function isFindPasswordFormValid(email: string, businessRegistrationNumber: string): boolean {
  return isValidEmail(email) && isValidBusinessNumber(businessRegistrationNumber);
}
