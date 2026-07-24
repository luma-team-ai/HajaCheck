// 로그인 화면 — HAJA-160(#157) — SpringBoot 사용자 도메인 Role enum과 값 일치
// Role은 auth 전용이 아니게 되어(admin 사용자 관리·AdminRoute도 사용) shared/constants로 승격했다
// (React_코드_컨벤션.md §1 "공유가 필요해지면 shared/로 승격"). 기존 import 경로 유지를 위해 재export.
// (파일 내 User.role에서도 써야 하므로 import + 재export 두 가지를 모두 한다)
import type { Role } from '../../shared/constants/roles';

export type { Role };

export interface User {
  id: number;
  email: string;
  name: string;
  role: Role;
  companyId: number | null;
  profileImageUrl: string | null;
  // 가입일시(BaseTimeEntity, 항상 존재) — 마이페이지 "내 프로필" 섹션(#744, HAJA-403)에서 사용.
  createdAt: string;
  // 소속 기업명 — 개인 회원/회사 미조회 시 null(#744, HAJA-403).
  companyName: string | null;
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
  // 개업일자 — 국세청 진위확인(#596)이 요구하는 필수값(ISO `yyyy-MM-dd`). #600.
  businessStartDate: string;
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

// 사업자 진위확인(#648 BE, #663 FE) — docs/_local/handoff/backend-648-bizverify-api.md 계약.
// 판정 결과는 언제나 200 + success:true(에러가 아니라 정상 응답 형태로 6종 result를 표현).
export type BusinessVerificationResult =
  | 'VERIFIED'
  | 'NOT_REGISTERED'
  | 'MISMATCH'
  | 'SUSPENDED'
  | 'CLOSED'
  | 'UNAVAILABLE';

export interface BusinessVerificationRequest {
  businessRegistrationNumber: string;
  representativeName: string;
  // ISO `yyyy-MM-dd` — `<input type="date">` 값 그대로 사용
  businessStartDate: string;
}

export interface BusinessVerificationResponse {
  result: BusinessVerificationResult;
  message: string;
}

// 사업자등록증 OCR 자동채움(#587) — docs/api-contract 계약: 각 필드는 인식 실패 시 null.
// 개업일자(businessStartDate)는 #598에서 4번째 자동채움 필드로 추가됨(ISO `yyyy-MM-dd`, nullable) — #600.
export interface BusinessLicenseOcrResponse {
  businessRegistrationNumber: string | null;
  companyName: string | null;
  representativeName: string | null;
  businessStartDate: string | null;
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
