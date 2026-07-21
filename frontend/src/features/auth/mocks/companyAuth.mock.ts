// 기업 인증 플로우 MSW 목 — HAJA-170(#187), 회원가입/아이디찾기 클릭 플로우가
// 끝까지 동작하도록 구성. 데이터가 많아 authApi.handlers.ts에서 분리(React_코드_컨벤션.md 지침)
// 비밀번호 찾기·새 비밀번호 설정 목은 passwordReset.mock.ts 로 분리(#301, HAJA-224)
// 가입 승인 대기 상태조회 목은 승인 대기 플로우 제거로 삭제(#481)
import { http, HttpResponse } from 'msw';
import type { ApiResponse } from '../../../shared/api/types';
import type {
  CompanySignupResponse,
  EmailAvailabilityResponse,
  IdInquiryResponse,
} from '../types';

// 이미 가입된 것으로 취급할 더미 값 — 중복 시나리오 데모/테스트용
export const MOCK_DUPLICATED_EMAIL = 'taken@check.com';
export const MOCK_DUPLICATED_BUSINESS_NUMBER = '9999999999';

// 아이디 찾기 성공 시나리오 더미 — 상호명 또는 대표자명 중 하나만 일치해도 매칭
export const MOCK_FIND_ID_BUSINESS_NUMBER = '1234567890';
export const MOCK_FIND_ID_COMPANY_NAME = '(주)하자체크';
export const MOCK_FIND_ID_REPRESENTATIVE_NAME = '김민수';
export const MOCK_FIND_ID_MASKED_EMAIL = 'ha***@check.com';

export const MOCK_SIGNUP_TOKEN = 'mock-signup-token';

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
];
