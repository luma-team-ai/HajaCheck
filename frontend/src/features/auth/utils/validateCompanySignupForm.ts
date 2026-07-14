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
  agreeToTerms: boolean;
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
    form.agreeToTerms
  );
}
