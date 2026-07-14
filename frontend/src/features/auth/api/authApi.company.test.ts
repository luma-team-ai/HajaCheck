// @vitest-environment jsdom
// axios가 baseURL='/api'(상대경로)를 XHR 어댑터로 resolve하려면 jsdom 환경이 필요
//
// 주의: signupCompany(multipart, File 파트 포함)의 실제 HTTP 라운드트립은 이 테스트에 포함하지
// 않는다 — msw(XMLHttpRequestInterceptor)+jsdom+undici 조합에서 File 파트가 있는 multipart를
// request.formData()로 파싱하면 undici 내부 webidl 어서션이 실패해 항상 네트워크 오류로 reject되는
// 환경 한계가 있음을 확인했다(텍스트 필드만 있는 multipart는 정상 동작 확인됨 — jsdom File/Node File
// 어느 쪽을 써도 동일). FormData 구성 로직은 authApi.buildCompanySignupFormData.test.ts에서 별도
// 단위 테스트하고, 실제 브라우저 dev 서버 클릭 플로우로 signupCompany를 검증했다.
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import {
  MOCK_DUPLICATED_EMAIL,
  MOCK_FIND_ID_BUSINESS_NUMBER,
  MOCK_FIND_ID_COMPANY_NAME,
  MOCK_PASSWORD_INQUIRY_BUSINESS_NUMBER,
  MOCK_PASSWORD_INQUIRY_EMAIL,
  MOCK_RESET_TOKEN,
  MOCK_SIGNUP_TOKEN,
  companyAuthHandlers,
  resetCompanyAuthMockState,
} from '../mocks/companyAuth.mock';
import { authApi } from './authApi';

const server = setupServer(...companyAuthHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  resetCompanyAuthMockState();
});
afterAll(() => server.close());

beforeEach(() => {
  resetCompanyAuthMockState();
});

describe('authApi.checkEmailAvailability', () => {
  it('가입되지 않은 이메일이면 available:true를 반환한다', async () => {
    const res = await authApi.checkEmailAvailability('new@check.com');
    expect(res.data).toEqual({ available: true });
  });

  it('이미 가입된 이메일이면 available:false를 반환한다', async () => {
    const res = await authApi.checkEmailAvailability(MOCK_DUPLICATED_EMAIL);
    expect(res.data).toEqual({ available: false });
  });
});

describe('authApi.getSignupStatus', () => {
  it('알 수 없는 토큰이면 AUTH_SIGNUP_TOKEN_INVALID로 reject된다', async () => {
    await expect(authApi.getSignupStatus('unknown-token')).rejects.toMatchObject({
      code: 'AUTH_SIGNUP_TOKEN_INVALID',
      status: 404,
    });
  });

  it('알려진 토큰의 첫 조회는 PENDING_REVIEW를 반환한다', async () => {
    const res = await authApi.getSignupStatus(MOCK_SIGNUP_TOKEN);
    expect(res.data.status).toBe('PENDING_REVIEW');
  });

  it('세 번째 조회부터는 APPROVED로 전환된다(새로고침 데모)', async () => {
    await authApi.getSignupStatus(MOCK_SIGNUP_TOKEN);
    await authApi.getSignupStatus(MOCK_SIGNUP_TOKEN);
    const res = await authApi.getSignupStatus(MOCK_SIGNUP_TOKEN);
    expect(res.data.status).toBe('APPROVED');
  });
});

describe('authApi.findLoginId', () => {
  it('사업자번호+상호명이 일치하면 마스킹 이메일을 반환한다', async () => {
    const res = await authApi.findLoginId({
      businessRegistrationNumber: MOCK_FIND_ID_BUSINESS_NUMBER,
      companyName: MOCK_FIND_ID_COMPANY_NAME,
      representativeName: '',
    });
    expect(res.data.maskedEmail).toContain('@check.com');
  });

  it('무매칭이면 AUTH_ACCOUNT_NOT_FOUND로 reject된다', async () => {
    await expect(
      authApi.findLoginId({
        businessRegistrationNumber: '000-00-00000',
        companyName: '없는회사',
        representativeName: '',
      }),
    ).rejects.toMatchObject({ code: 'AUTH_ACCOUNT_NOT_FOUND', status: 404 });
  });
});

describe('authApi.passwordInquiry', () => {
  it('이메일+사업자번호가 일치하면 resetToken을 반환한다', async () => {
    const res = await authApi.passwordInquiry({
      email: MOCK_PASSWORD_INQUIRY_EMAIL,
      businessRegistrationNumber: MOCK_PASSWORD_INQUIRY_BUSINESS_NUMBER,
    });
    expect(res.data).toMatchObject({ resetToken: MOCK_RESET_TOKEN });
  });

  it('불일치면 AUTH_VERIFICATION_FAILED(400)로 reject된다', async () => {
    await expect(
      authApi.passwordInquiry({
        email: 'wrong@check.com',
        businessRegistrationNumber: MOCK_PASSWORD_INQUIRY_BUSINESS_NUMBER,
      }),
    ).rejects.toMatchObject({ code: 'AUTH_VERIFICATION_FAILED', status: 400 });
  });
});

describe('authApi.passwordReset', () => {
  it('유효한 resetToken이면 성공(data:null)한다', async () => {
    const res = await authApi.passwordReset({
      resetToken: MOCK_RESET_TOKEN,
      newPassword: 'newpass1234',
    });
    expect(res.data).toBeNull();
  });

  it('무효한 resetToken이면 AUTH_RESET_TOKEN_INVALID(400)로 reject된다', async () => {
    await expect(
      authApi.passwordReset({ resetToken: 'invalid-token', newPassword: 'newpass1234' }),
    ).rejects.toMatchObject({ code: 'AUTH_RESET_TOKEN_INVALID', status: 400 });
  });
});
