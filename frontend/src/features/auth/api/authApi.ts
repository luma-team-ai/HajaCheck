import { api } from '../../../shared/api/axios';
import { AI_TIMEOUT_MS, uploadTimeoutMs } from '../../../shared/api/timeouts';
import {
  BUSINESS_LICENSE_OCR_PATH,
  BUSINESS_VERIFICATION_PATH,
  COMPANY_SIGNUP_PATH,
  EMAIL_AVAILABILITY_PATH,
  ID_INQUIRY_PATH,
  INVITE_CODE_REDEEM_PATH,
  PASSWORD_RESET_PATH,
  PASSWORD_RESET_REQUEST_PATH,
} from '../constants';
import type {
  BusinessLicenseOcrResponse,
  BusinessVerificationRequest,
  BusinessVerificationResponse,
  CompanySignupRequest,
  CompanySignupResponse,
  EmailAvailabilityResponse,
  IdInquiryRequest,
  IdInquiryResponse,
  InviteCodeRedeemRequest,
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
  formData.append('businessStartDate', body.businessStartDate);
  formData.append('address', body.address);
  formData.append('addressDetail', body.addressDetail);
  formData.append('agreeTermsOfService', String(body.agreeTermsOfService));
  formData.append('agreePrivacyPolicy', String(body.agreePrivacyPolicy));
  formData.append('businessRegistrationFile', body.businessRegistrationFile);
  return formData;
}

export const authApi = {
  // 로그인 엔드포인트는 화면(포털)마다 분리돼 있다(#1513, BE #1514/PR #1533) — 서버가 엔드포인트별로
  // 허용 role을 강제하고, 허용되지 않은 role은 인증에 성공해도 세션을 발급하지 않고 403
  // AUTH_ROLE_NOT_ALLOWED를 반환한다. 즉 role 판정은 서버 책임이며 프론트는 "어느 문으로 두드리는가"만
  // 정확히 고르면 된다(예전처럼 로그인 후 role을 보고 logout()으로 되돌리는 사후 처리는 필요 없다).
  // 화면 ↔ 엔드포인트 대응을 여기서 어기면 그 화면 로그인이 통째로 403이 되므로, 각 화면 테스트가
  // "자기 경로로 POST 하는지"를 고정한다.
  // jsdoc 표기 규약: `{화면 경로}(SPA 라우트) 전용 — POST {API 경로}, {허용 role}`.
  // 둘을 명시적으로 구분한다 — 상담원만 화면 경로와 API 경로가 크게 다른데(/counsel-console/login
  // vs /auth/counselor/login), 한쪽만 적어두면 후속 작업자가 "불일치"로 오인해 엔드포인트 쪽을
  // 화면 경로에 맞추는 회귀를 낼 수 있다(이 파일의 경로 레코더 테스트가 막으려는 바로 그 회귀).
  /** 기업회원 로그인 화면(/login) 전용 — POST /auth/login, ADMIN·INSPECTOR·USER 허용 */
  login: (body: LoginRequest) => api.post<UserResponse>('/auth/login', body),
  /** 플랫폼 관리자 로그인 화면(/platform-admin/login) 전용 — POST /auth/platform-admin/login, PLATFORM_ADMIN 허용 */
  platformAdminLogin: (body: LoginRequest) =>
    api.post<UserResponse>('/auth/platform-admin/login', body),
  /** 상담원 로그인 화면(/counsel-console/login) 전용 — POST /auth/counselor/login, COUNSELOR 허용 */
  counselorLogin: (body: LoginRequest) => api.post<UserResponse>('/auth/counselor/login', body),
  logout: () => api.post('/auth/logout'),
  // 세션 확인(부트스트랩 AuthGate·로그인 화면 마운트 시 CSRF 프리밍) — 401은 미로그인으로 간주(호출부에서 무시).
  // skipAuthRedirect: 401이어도 전역 /login 하드 리다이렉트를 하지 않는다 — 공개 랜딩('/')이 안 뜨던 회귀 방지(#276).
  getMe: () => api.get<UserResponse>('/users/me', { skipAuthRedirect: true }),

  // 기업 인증 플로우 — HAJA-170(#187)
  // 이메일 중복확인 겸 인증 폼(회원가입 외) 마운트 시 CSRF 쿠키 프라이밍 용도로도 사용(useCsrfPrime)
  checkEmailAvailability: (email: string) =>
    api.get<EmailAvailabilityResponse>(EMAIL_AVAILABILITY_PATH, { params: { email } }),
  signupCompany: (body: CompanySignupRequest) =>
    api.post<CompanySignupResponse>(COMPANY_SIGNUP_PATH, toCompanySignupFormData(body), {
      // 사업자등록증 파일(최대 10MB, constants.BUSINESS_LICENSE_MAX_SIZE_BYTES)을 함께 올리고,
      // 서버는 그 김에 국세청 진위확인까지 동기로 호출한다(biz-verify read-timeout 5초, 재시도 없음).
      // 파일 전송 시간이 전역 30초를 잠식하므로 크기에 비례한 상한을 준다(#1598, 근거는 timeouts.ts).
      timeout: uploadTimeoutMs(body.businessRegistrationFile.size),
    }),
  // 사업자 진위확인(#648 BE, #663 FE) — 회원가입 제출 전 [진위확인] 버튼, 비로그인 공개 엔드포인트.
  // 판정 결과(VERIFIED 등 6종)는 항상 200으로 오므로 result 필드로 분기한다.
  verifyBusiness: (body: BusinessVerificationRequest) =>
    api.post<BusinessVerificationResponse>(BUSINESS_VERIFICATION_PATH, body),
  // 사업자등록증 OCR 자동채움(#587) — 비로그인 공개 엔드포인트, 파일 파라미터명은 가입 API와
  // 동일하게 businessRegistrationFile을 사용(계약 정합).
  businessLicenseOcr: (file: File) => {
    const formData = new FormData();
    formData.append('businessRegistrationFile', file);
    return api.post<BusinessLicenseOcrResponse>(BUSINESS_LICENSE_OCR_PATH, formData, {
      // 이름만 봐서는 AI 호출인 줄 모르는 경로다 — 서버가 ai-server로 프록시해 RapidOCR + LLM을
      // 돌리므로 스프링 ai.server read-timeout 150초가 그대로 걸린다. 전역 30초면 정상 OCR이
      // 끊긴다. AI 처리 천장 + 파일 전송 시간(#1598, 근거는 timeouts.ts).
      timeout: uploadTimeoutMs(file.size, AI_TIMEOUT_MS),
    });
  },
  findLoginId: (body: IdInquiryRequest) => api.post<IdInquiryResponse>(ID_INQUIRY_PATH, body),
  // 비밀번호 찾기 — 이메일 링크 방식(#301, HAJA-224)
  requestPasswordReset: (body: PasswordResetLinkRequest) =>
    api.post<PasswordResetLinkResponse>(PASSWORD_RESET_REQUEST_PATH, body),
  resetPassword: (body: PasswordResetRequest) =>
    api.post<PasswordResetResponse>(PASSWORD_RESET_PATH, body),
  // 초대 코드 redeem(#794 backend PR #801, #799) — WAITING 사용자 전용. 성공 시 회사 배선된
  // 최신 UserResponse(status=ACTIVE)를 반환한다.
  redeemInviteCode: (body: InviteCodeRedeemRequest) =>
    api.post<UserResponse>(INVITE_CODE_REDEEM_PATH, body),
};
