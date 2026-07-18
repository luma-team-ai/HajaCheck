// 로그인 화면 — HAJA-160(#157) — SpringBoot 사용자 도메인 Role enum과 값 일치
// feature 간 직접 import 금지(React_코드_컨벤션.md §1) — auth 전용 로컬 정의

export type Role = 'ADMIN' | 'INSPECTOR' | 'USER' | 'COUNSELOR';

export interface User {
  id: number;
  email: string;
  name: string;
  role: Role;
  companyId: number | null;
  profileImageUrl: string | null;
}

// 백엔드 응답 DTO 형태 — 현재는 User와 동일 필드
export type UserResponse = User;

export interface LoginRequest {
  loginId: string;
  password: string;
}

// 기업 인증 플로우 — HAJA-170(#187) — docs/api-contract/contract.md "기업 인증 플로우 Contract v1"
export type CompanyStatus = 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED';

export interface CompanySignupRequest {
  email: string;
  password: string;
  companyName: string;
  businessRegistrationNumber: string;
  representativeName: string;
  address: string;
  addressDetail: string;
  agreeTermsOfService: boolean;
  agreePrivacyPolicy: boolean;
  businessRegistrationFile: File;
}

export interface CompanySignupResponse {
  companyId: number;
  maskedEmail: string;
  status: CompanyStatus;
  signupToken: string;
}

export interface EmailAvailabilityResponse {
  available: boolean;
}

export interface IdInquiryRequest {
  businessRegistrationNumber: string;
  companyName: string;
  representativeName: string;
}

export interface IdInquiryResponse {
  maskedEmail: string;
}

// 비밀번호 찾기 — 이메일 링크 방식(#301, HAJA-224) — docs/api-contract/contract.md "비밀번호 찾기 1·2단계"
export interface PasswordResetLinkRequest {
  email: string;
}

export interface PasswordResetLinkResponse {
  requested: boolean;
}

export interface PasswordResetRequest {
  token: string;
  newPassword: string;
}

export interface PasswordResetResponse {
  reset: boolean;
}

export interface SignupStatusResponse {
  status: CompanyStatus;
  companyName: string;
  rejectionReason: string | null;
}
