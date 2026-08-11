// 로그인 화면 — HAJA-160(#157) — SpringBoot 사용자 도메인 Role enum과 값 일치
// Role은 auth 전용이 아니게 되어(admin 사용자 관리·AdminRoute도 사용) shared/constants로 승격했다
// (React_코드_컨벤션.md §1 "공유가 필요해지면 shared/로 승격"). 기존 import 경로 유지를 위해 재export.
// (파일 내 User.role에서도 써야 하므로 import + 재export 두 가지를 모두 한다)
import type { Role } from '../../shared/constants/roles';

export type { Role };

// 사용자 계정 상태 — 백엔드 UserStatus enum(#794, PR #801)과 값 일치.
// WAITING: 소셜 최초 가입 직후 company_id 없음 — 초대 코드를 redeem해야 ACTIVE로 전환된다.
export type UserStatus = 'ACTIVE' | 'SUSPENDED' | 'WAITING';

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
  // 초대 코드 입력 화면 분기용(#794, #799) — WAITING이면 ProtectedRoute가 INVITE_CODE_ROUTE로 리다이렉트.
  status: UserStatus;
  // 데모 계정 여부(#1627, 백엔드 #1626 계약) — true면 DemoModeBanner가 "데이터는 매일 초기화됩니다"
  // 안내를 노출한다. optional인 이유: 백엔드가 이 필드를 아직 내려주지 않는 환경(미배포)에서도
  // undefined로 안전하게 폴백해(=배너 미노출) 화면이 깨지지 않아야 하기 때문 — 이 필드가 반드시
  // 오는 것을 전제로 코드를 짜면 안 된다.
  isDemo?: boolean;
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

// 초대 코드 redeem(#794 backend PR #801, #799) — 성공 시 companyId 배선 + status=ACTIVE로 전환된
// 최신 UserResponse를 그대로 돌려준다(authStore 갱신용).
export interface InviteCodeRedeemRequest {
  code: string;
}
