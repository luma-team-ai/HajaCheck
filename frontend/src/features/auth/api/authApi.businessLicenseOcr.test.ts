// @vitest-environment jsdom
// 사업자등록증 OCR 자동채움(#587) — authApi.businessLicenseOcr의 실제 HTTP 라운드트립 테스트.
// authApi.company.test.ts의 known limitation(File 파트가 있는 multipart를 핸들러가
// request.formData()로 파싱하면 msw+jsdom+undici 조합에서 항상 네트워크 오류로 reject됨)과 달리,
// 이 엔드포인트의 목 핸들러(companyAuth.mock.ts)는 요청 본문을 파싱하지 않으므로 File 파트를
// 포함한 실제 라운드트립도 안정적으로 재현된다(#587 구현 조사에서 확인).
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import {
  MOCK_OCR_BUSINESS_NUMBER,
  MOCK_OCR_COMPANY_NAME,
  MOCK_OCR_REPRESENTATIVE_NAME,
  companyAuthHandlers,
} from '../mocks/companyAuth.mock';
import { authApi } from './authApi';

const server = setupServer(...companyAuthHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function makeFile(type = 'image/png') {
  return new File(['dummy'], 'license.png', { type });
}

describe('authApi.businessLicenseOcr', () => {
  it('성공 시 3필드를 반환한다', async () => {
    const res = await authApi.businessLicenseOcr(makeFile());
    expect(res.data).toEqual({
      businessRegistrationNumber: MOCK_OCR_BUSINESS_NUMBER,
      companyName: MOCK_OCR_COMPANY_NAME,
      representativeName: MOCK_OCR_REPRESENTATIVE_NAME,
    });
  });

  it('일부 필드 인식 실패 시 해당 필드만 null로 내려온다', async () => {
    server.use(
      http.post('/api/auth/business-license/ocr', () => {
        const partial: ApiResponse<{
          businessRegistrationNumber: string | null;
          companyName: string | null;
          representativeName: string | null;
        }> = {
          success: true,
          data: { businessRegistrationNumber: MOCK_OCR_BUSINESS_NUMBER, companyName: null, representativeName: null },
        };
        return HttpResponse.json(partial);
      }),
    );

    const res = await authApi.businessLicenseOcr(makeFile());
    expect(res.data).toEqual({
      businessRegistrationNumber: MOCK_OCR_BUSINESS_NUMBER,
      companyName: null,
      representativeName: null,
    });
  });

  it('400(FILE_INVALID_TYPE)이면 status와 함께 reject된다', async () => {
    server.use(
      http.post('/api/auth/business-license/ocr', () => {
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: { code: 'FILE_INVALID_TYPE', message: '지원하지 않는 파일 형식입니다.' },
        };
        return HttpResponse.json(failure, { status: 400 });
      }),
    );

    await expect(authApi.businessLicenseOcr(makeFile())).rejects.toMatchObject({
      code: 'FILE_INVALID_TYPE',
      status: 400,
    });
  });

  it('429(rate-limit)면 status와 함께 reject된다', async () => {
    server.use(
      http.post('/api/auth/business-license/ocr', () => {
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: { code: 'RATE_LIMITED', message: '잠시 후 다시 시도해 주세요.' },
        };
        return HttpResponse.json(failure, { status: 429 });
      }),
    );

    await expect(authApi.businessLicenseOcr(makeFile())).rejects.toMatchObject({
      code: 'RATE_LIMITED',
      status: 429,
    });
  });

  it('5xx(ai-server 실패)면 status와 함께 reject된다', async () => {
    server.use(
      http.post('/api/auth/business-license/ocr', () => {
        return new HttpResponse(null, { status: 502 });
      }),
    );

    await expect(authApi.businessLicenseOcr(makeFile())).rejects.toMatchObject({ status: 502 });
  });
});
