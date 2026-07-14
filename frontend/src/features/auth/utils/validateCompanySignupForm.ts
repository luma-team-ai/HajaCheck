// 기업 회원가입 폼 검증 — HAJA-170(#187), 계약(contract.md) 필드 검증 규칙과 정합
import { doPasswordsMatch, isValidBusinessNumber, isValidEmail, isValidPassword } from './authFormValidators';
import { validateBusinessLicenseFile } from './validateBusinessLicenseFile';

export interface CompanySignupFormValues {
  email: string;
  password: string;
  confirmPassword: string;
  companyName: string;
  businessRegistrationNumber: string;
  representativeName: string;
  address: string;
  businessRegistrationFile: File | null;
  // 이용약관 동의와 개인정보 수집·이용 동의는 국내 개인정보보호법상 별도 동의가 필요해 분리
  // (PR머신 P2, 백엔드는 이미 두 필드를 별개로 받아 UserConsent 2건 생성)
  agreeTermsOfService: boolean;
  agreePrivacyPolicy: boolean;
}

export function isCompanySignupFormValid(form: CompanySignupFormValues): boolean {
  return (
    isValidEmail(form.email) &&
    isValidPassword(form.password) &&
    doPasswordsMatch(form.password, form.confirmPassword) &&
    form.companyName.trim().length > 0 &&
    isValidBusinessNumber(form.businessRegistrationNumber) &&
    form.representativeName.trim().length > 0 &&
    form.address.trim().length > 0 &&
    validateBusinessLicenseFile(form.businessRegistrationFile) === null &&
    form.agreeTermsOfService &&
    form.agreePrivacyPolicy
  );
}
