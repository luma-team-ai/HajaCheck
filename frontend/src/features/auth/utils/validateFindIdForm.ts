// 기업 아이디 찾기 폼 검증 — HAJA-170(#187) — 계약: 상호명/대표자명 중 최소 1개 필수
import { isValidBusinessNumber } from './authFormValidators';

export function isFindIdFormValid(
  businessRegistrationNumber: string,
  companyName: string,
  representativeName: string,
): boolean {
  return (
    isValidBusinessNumber(businessRegistrationNumber) &&
    (companyName.trim().length > 0 || representativeName.trim().length > 0)
  );
}
