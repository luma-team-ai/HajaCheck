import { api } from '../../../shared/api/axios';
import {
  COMPANY_SIGNUP_PATH,
  COMPANY_SIGNUP_STATUS_PATH,
  EMAIL_AVAILABILITY_PATH,
  ID_INQUIRY_PATH,
  PASSWORD_INQUIRY_PATH,
  PASSWORD_RESET_PATH,
} from '../constants';
import type {
  CompanySignupRequest,
  CompanySignupResponse,
  EmailAvailabilityResponse,
  IdInquiryRequest,
  IdInquiryResponse,
  LoginRequest,
  PasswordInquiryRequest,
  PasswordInquiryResponse,
  PasswordResetRequest,
  SignupStatusResponse,
  UserResponse,
} from '../types';

// 기업 회원가입 요청을 multipart/form-data로 변환 — 계약(contract.md) 필드명과 1:1
// export: 순수 변환 로직만 단위 테스트하기 위함(authApi.buildCompanySignupFormData.test.ts) —
// 파일(File) 파트를 포함한 실제 HTTP 라운드트립은 msw+jsdom+undici 조합의 알려진 환경 한계로
// 이 프로젝트의 테스트 환경에서 안정적으로 재현되지 않아 별도로 검증한다.
export function toCompanySignupFormData(body: CompanySignupRequest): FormData {
  const formData = new FormData();
  formData.append('email', body.email);
  formData.append('password', body.password);
  formData.append('companyName', body.companyName);
  formData.append('businessRegistrationNumber', body.businessRegistrationNumber);
  formData.append('representativeName', body.representativeName);
  formData.append('address', body.address);
  formData.append('addressDetail', body.addressDetail);
  formData.append('agreeTermsOfService', String(body.agreeTermsOfService));
  formData.append('agreePrivacyPolicy', String(body.agreePrivacyPolicy));
  formData.append('businessRegistrationFile', body.businessRegistrationFile);
  return formData;
}

export const authApi = {
  login: (body: LoginRequest) => api.post<UserResponse>('/auth/login', body),
  logout: () => api.post('/auth/logout'),
  // 로그인 화면 마운트 시 CSRF 쿠키 프리밍 겸 세션 확인 — 401은 미로그인으로 간주(호출부에서 무시)
  getMe: () => api.get<UserResponse>('/users/me'),

  // 기업 인증 플로우 — HAJA-170(#187)
  // 이메일 중복확인 겸 인증 폼(회원가입 외) 마운트 시 CSRF 쿠키 프라이밍 용도로도 사용(useCsrfPrime)
  checkEmailAvailability: (email: string) =>
    api.get<EmailAvailabilityResponse>(EMAIL_AVAILABILITY_PATH, { params: { email } }),
  signupCompany: (body: CompanySignupRequest) =>
    api.post<CompanySignupResponse>(COMPANY_SIGNUP_PATH, toCompanySignupFormData(body)),
  getSignupStatus: (signupToken: string) =>
    api.get<SignupStatusResponse>(COMPANY_SIGNUP_STATUS_PATH, { params: { token: signupToken } }),
  findLoginId: (body: IdInquiryRequest) => api.post<IdInquiryResponse>(ID_INQUIRY_PATH, body),
  passwordInquiry: (body: PasswordInquiryRequest) =>
    api.post<PasswordInquiryResponse>(PASSWORD_INQUIRY_PATH, body),
  passwordReset: (body: PasswordResetRequest) => api.post<null>(PASSWORD_RESET_PATH, body),
};
