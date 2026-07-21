import { api } from '../../../shared/api/axios';
import {
  COMPANY_SIGNUP_PATH,
  EMAIL_AVAILABILITY_PATH,
  ID_INQUIRY_PATH,
  PASSWORD_RESET_PATH,
  PASSWORD_RESET_REQUEST_PATH,
} from '../constants';
import type {
  CompanySignupRequest,
  CompanySignupResponse,
  EmailAvailabilityResponse,
  IdInquiryRequest,
  IdInquiryResponse,
  LoginRequest,
  PasswordResetLinkRequest,
  PasswordResetLinkResponse,
  PasswordResetRequest,
  PasswordResetResponse,
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
  // 세션 확인(부트스트랩 AuthGate·로그인 화면 마운트 시 CSRF 프리밍) — 401은 미로그인으로 간주(호출부에서 무시).
  // skipAuthRedirect: 401이어도 전역 /login 하드 리다이렉트를 하지 않는다 — 공개 랜딩('/')이 안 뜨던 회귀 방지(#276).
  getMe: () => api.get<UserResponse>('/users/me', { skipAuthRedirect: true }),

  // 기업 인증 플로우 — HAJA-170(#187)
  // 이메일 중복확인 겸 인증 폼(회원가입 외) 마운트 시 CSRF 쿠키 프라이밍 용도로도 사용(useCsrfPrime)
  checkEmailAvailability: (email: string) =>
    api.get<EmailAvailabilityResponse>(EMAIL_AVAILABILITY_PATH, { params: { email } }),
  signupCompany: (body: CompanySignupRequest) =>
    api.post<CompanySignupResponse>(COMPANY_SIGNUP_PATH, toCompanySignupFormData(body)),
  findLoginId: (body: IdInquiryRequest) => api.post<IdInquiryResponse>(ID_INQUIRY_PATH, body),
  // 비밀번호 찾기 — 이메일 링크 방식(#301, HAJA-224)
  requestPasswordReset: (body: PasswordResetLinkRequest) =>
    api.post<PasswordResetLinkResponse>(PASSWORD_RESET_REQUEST_PATH, body),
  resetPassword: (body: PasswordResetRequest) =>
    api.post<PasswordResetResponse>(PASSWORD_RESET_PATH, body),
};
