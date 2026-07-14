// 기업 인증 플로우 MSW 목 — HAJA-170(#187), 5화면 클릭 플로우가 끝까지 동작하도록 구성
// 데이터가 많아 authApi.handlers.ts에서 분리(React_코드_컨벤션.md 지침)
import { http, HttpResponse } from 'msw';
import type { ApiResponse } from '../../../shared/api/types';
import type {
  CompanySignupResponse,
  EmailAvailabilityResponse,
  IdInquiryResponse,
  PasswordInquiryResponse,
  SignupStatusResponse,
} from '../types';

// 이미 가입된 것으로 취급할 더미 값 — 중복 시나리오 데모/테스트용
export const MOCK_DUPLICATED_EMAIL = 'taken@check.com';
export const MOCK_DUPLICATED_BUSINESS_NUMBER = '9999999999';

// 아이디 찾기 성공 시나리오 더미 — 상호명 또는 대표자명 중 하나만 일치해도 매칭
export const MOCK_FIND_ID_BUSINESS_NUMBER = '1234567890';
export const MOCK_FIND_ID_COMPANY_NAME = '(주)하자체크';
export const MOCK_FIND_ID_REPRESENTATIVE_NAME = '김민수';
export const MOCK_FIND_ID_MASKED_EMAIL = 'ha***@check.com';

// 비밀번호 찾기 1단계 성공 시나리오 더미
export const MOCK_PASSWORD_INQUIRY_EMAIL = 'haja@check.com';
export const MOCK_PASSWORD_INQUIRY_BUSINESS_NUMBER = '1234567890';
export const MOCK_RESET_TOKEN = 'mock-reset-token';

export const MOCK_SIGNUP_TOKEN = 'mock-signup-token';

let signupStatusCheckCount = 0;

// 테스트 간 모듈 스코프 상태(새로고침 시뮬레이션 카운터) 초기화용
export function resetCompanyAuthMockState(): void {
  signupStatusCheckCount = 0;
}

function normalizeDigits(value: string): string {
  return value.replace(/\D/g, '');
}

function maskEmail(email: string): string {
  const [local, domain] = email.split('@');
  if (!domain) return email;
  const visible = local.slice(0, 2);
  return `${visible}${'*'.repeat(Math.max(local.length - visible.length, 3))}@${domain}`;
}

export const companyAuthHandlers = [
  http.get('/api/auth/email-availability', ({ request }) => {
    const url = new URL(request.url);
    const email = url.searchParams.get('email') ?? '';
    const success: ApiResponse<EmailAvailabilityResponse> = {
      success: true,
      data: { available: email !== MOCK_DUPLICATED_EMAIL },
    };
    return HttpResponse.json(success);
  }),

  http.post('/api/auth/companies', async ({ request }) => {
    const formData = await request.formData();
    const email = String(formData.get('email') ?? '');
    const businessRegistrationNumber = normalizeDigits(
      String(formData.get('businessRegistrationNumber') ?? ''),
    );
    const file = formData.get('businessRegistrationFile');

    // instanceof File 대신 duck-typing으로 판별 — 테스트 환경(jsdom)의 File과 undici가
    // 멀티파트 파싱 후 만들어내는 File의 클래스 아이덴티티가 달라 instanceof가 깨질 수 있음
    if (!file || typeof file === 'string') {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'FILE_REQUIRED', message: '사업자등록증 파일을 첨부해 주세요.' },
      };
      return HttpResponse.json(failure, { status: 400 });
    }

    if (email === MOCK_DUPLICATED_EMAIL) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'AUTH_EMAIL_DUPLICATED', message: '이미 가입된 이메일입니다.' },
      };
      return HttpResponse.json(failure, { status: 409 });
    }

    if (businessRegistrationNumber === MOCK_DUPLICATED_BUSINESS_NUMBER) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: {
          code: 'AUTH_BUSINESS_NUMBER_DUPLICATED',
          message: '이미 등록된 사업자등록번호입니다.',
        },
      };
      return HttpResponse.json(failure, { status: 409 });
    }

    const success: ApiResponse<CompanySignupResponse> = {
      success: true,
      data: {
        companyId: 12,
        maskedEmail: maskEmail(email),
        status: 'PENDING_REVIEW',
        signupToken: MOCK_SIGNUP_TOKEN,
      },
    };
    return HttpResponse.json(success, { status: 201 });
  }),

  http.get('/api/auth/companies/status', ({ request }) => {
    const url = new URL(request.url);
    const token = url.searchParams.get('token');

    if (token !== MOCK_SIGNUP_TOKEN) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'AUTH_SIGNUP_TOKEN_INVALID', message: '유효하지 않은 요청입니다.' },
      };
      return HttpResponse.json(failure, { status: 404 });
    }

    signupStatusCheckCount += 1;
    // 3번째 조회부터 승인완료로 전환 — "가입 상태 새로고침" 클릭 플로우 데모용
    const status = signupStatusCheckCount >= 3 ? 'APPROVED' : 'PENDING_REVIEW';

    const success: ApiResponse<SignupStatusResponse> = {
      success: true,
      data: { status, companyName: MOCK_FIND_ID_COMPANY_NAME, rejectionReason: null },
    };
    return HttpResponse.json(success);
  }),

  http.post('/api/auth/id-inquiry', async ({ request }) => {
    const body = (await request.json()) as {
      businessRegistrationNumber: string;
      companyName: string;
      representativeName: string;
    };
    const businessRegistrationNumber = normalizeDigits(body.businessRegistrationNumber);

    const isMatch =
      businessRegistrationNumber === MOCK_FIND_ID_BUSINESS_NUMBER &&
      (body.companyName === MOCK_FIND_ID_COMPANY_NAME ||
        body.representativeName === MOCK_FIND_ID_REPRESENTATIVE_NAME);

    if (!isMatch) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'AUTH_ACCOUNT_NOT_FOUND', message: '일치하는 계정을 찾을 수 없습니다.' },
      };
      return HttpResponse.json(failure, { status: 404 });
    }

    const success: ApiResponse<IdInquiryResponse> = {
      success: true,
      data: { maskedEmail: MOCK_FIND_ID_MASKED_EMAIL },
    };
    return HttpResponse.json(success);
  }),

  http.post('/api/auth/password-inquiry', async ({ request }) => {
    const body = (await request.json()) as { email: string; businessRegistrationNumber: string };
    const businessRegistrationNumber = normalizeDigits(body.businessRegistrationNumber);

    const isMatch =
      body.email === MOCK_PASSWORD_INQUIRY_EMAIL &&
      businessRegistrationNumber === MOCK_PASSWORD_INQUIRY_BUSINESS_NUMBER;

    if (!isMatch) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: {
          code: 'AUTH_VERIFICATION_FAILED',
          message: '입력하신 정보와 일치하는 계정을 찾을 수 없습니다.',
        },
      };
      return HttpResponse.json(failure, { status: 400 });
    }

    const success: ApiResponse<PasswordInquiryResponse> = {
      success: true,
      data: {
        resetToken: MOCK_RESET_TOKEN,
        maskedEmail: maskEmail(MOCK_PASSWORD_INQUIRY_EMAIL),
        expiresInSeconds: 600,
      },
    };
    return HttpResponse.json(success);
  }),

  http.post('/api/auth/password-reset', async ({ request }) => {
    const body = (await request.json()) as { resetToken: string; newPassword: string };

    if (body.resetToken !== MOCK_RESET_TOKEN) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: {
          code: 'AUTH_RESET_TOKEN_INVALID',
          message: '재설정 링크가 만료되었거나 유효하지 않습니다.',
        },
      };
      return HttpResponse.json(failure, { status: 400 });
    }

    const success: ApiResponse<null> = { success: true, data: null };
    return HttpResponse.json(success);
  }),
];
