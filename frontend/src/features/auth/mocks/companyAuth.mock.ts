// 기업 인증 플로우 MSW 목 — HAJA-170(#187), 회원가입/아이디찾기 클릭 플로우가
// 끝까지 동작하도록 구성. 데이터가 많아 authApi.handlers.ts에서 분리(React_코드_컨벤션.md 지침)
// 비밀번호 찾기·새 비밀번호 설정 목은 passwordReset.mock.ts 로 분리(#301, HAJA-224)
// 가입 승인 대기 상태조회 목은 승인 대기 플로우 제거로 삭제(#481)
import { http, HttpResponse } from 'msw';
import type { ApiResponse } from '../../../shared/api/types';
import type {
  BusinessLicenseOcrResponse,
  BusinessVerificationRequest,
  BusinessVerificationResponse,
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
export const MOCK_FIND_ID_MASKED_EMAIL = 'h***@c***.com';

export const MOCK_SIGNUP_TOKEN = 'mock-signup-token';

// 사업자 진위확인(#648 BE, #663 FE) 기본 성공 시나리오 — 이 3필드로 확인하면 VERIFIED.
// 그 외 조합은 기본적으로 NOT_REGISTERED로 응답(개별 결과 6종 시나리오는 테스트에서 server.use로 override).
export const MOCK_VERIFIED_BUSINESS_NUMBER = '1234567890';
export const MOCK_VERIFIED_REPRESENTATIVE_NAME = '김대표';
export const MOCK_VERIFIED_BUSINESS_START_DATE = '2015-03-02';
export const MOCK_VERIFIED_MESSAGE = '사업자 정보가 국세청 등록정보와 일치합니다.';
export const MOCK_NOT_REGISTERED_MESSAGE = '국세청에 등록되지 않은 사업자입니다.';

// 사업자등록증 OCR 자동채움(#587) 기본 성공 시나리오 — 핸들러가 request.formData()를 파싱하지
// 않아야 msw(node)+jsdom+undici 조합에서 File 파트가 있는 multipart도 안정적으로 라운드트립된다
// (authApi.company.test.ts 상단 주석의 known limitation과 달리, 요청 본문을 읽지 않으면 그 이슈를
// 피할 수 있음을 확인 후 채택 — #587 구현 조사).
export const MOCK_OCR_BUSINESS_NUMBER = '1234567890';
export const MOCK_OCR_COMPANY_NAME = '(주)오씨알테스트';
export const MOCK_OCR_REPRESENTATIVE_NAME = '박대표';
// 개업일자 자동채움(#598 응답 확장, #600 FE 반영) — ISO `yyyy-MM-dd`
export const MOCK_OCR_BUSINESS_START_DATE = '2015-03-02';

function normalizeDigits(value: string): string {
  return value.replace(/\D/g, '');
}

// 백엔드 EmailMasker(backend/src/main/java/com/hajacheck/global/util/EmailMasker.java)와
// 동일 규칙 — 로컬파트 첫 1자+"***", 도메인은 마지막 '.' 기준 host/TLD 분리 후 host 첫 1자+"***"+".TLD".
// 예) haja@check.com → h***@c***.com (#523, HAJA-312 — 백엔드 규칙 강화 #524 정합)
const MASK = '***';
const REVEAL = 1;

function maskDomain(domain: string): string {
  const dot = domain.lastIndexOf('.');
  if (dot <= 0 || dot === domain.length - 1) return MASK;
  const host = domain.slice(0, dot);
  const tld = domain.slice(dot + 1);
  return `${host.slice(0, REVEAL)}${MASK}.${tld}`;
}

function maskEmail(email: string): string {
  const at = email.lastIndexOf('@');
  if (at <= 0) return MASK;
  const local = email.slice(0, at);
  const domain = email.slice(at + 1);
  if (!domain) return MASK;
  return `${local.slice(0, REVEAL)}${MASK}@${maskDomain(domain)}`;
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

  http.post('/api/auth/business-verification', async ({ request }) => {
    const body = (await request.json()) as BusinessVerificationRequest;
    const businessRegistrationNumber = normalizeDigits(body.businessRegistrationNumber);

    const isVerified =
      businessRegistrationNumber === MOCK_VERIFIED_BUSINESS_NUMBER &&
      body.representativeName === MOCK_VERIFIED_REPRESENTATIVE_NAME &&
      body.businessStartDate === MOCK_VERIFIED_BUSINESS_START_DATE;

    const success: ApiResponse<BusinessVerificationResponse> = {
      success: true,
      data: isVerified
        ? { result: 'VERIFIED', message: MOCK_VERIFIED_MESSAGE }
        : { result: 'NOT_REGISTERED', message: MOCK_NOT_REGISTERED_MESSAGE },
    };
    return HttpResponse.json(success);
  }),

  http.post('/api/auth/business-license/ocr', () => {
    const success: ApiResponse<BusinessLicenseOcrResponse> = {
      success: true,
      data: {
        businessRegistrationNumber: MOCK_OCR_BUSINESS_NUMBER,
        companyName: MOCK_OCR_COMPANY_NAME,
        representativeName: MOCK_OCR_REPRESENTATIVE_NAME,
        businessStartDate: MOCK_OCR_BUSINESS_START_DATE,
      },
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
];
