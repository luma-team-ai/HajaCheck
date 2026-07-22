// @vitest-environment jsdom
// 사업자등록증 OCR 자동채움(#587) — 파일 선택 시 jpeg/png만 OCR을 호출해 3필드를 자동채움하고,
// 실패는 조용히 폴백(수동 입력 유지)하며, PDF는 OCR을 호출하지 않는지 검증한다.
// 실제 HTTP 라운드트립: companyAuth.mock.ts의 OCR 핸들러는 request.formData()를 파싱하지 않아
// msw+jsdom+undici 조합에서도 File 파트를 포함한 요청이 안정적으로 성공/실패 응답을 받는다
// (authApi.businessLicenseOcr.test.ts에서 동일 방식으로 검증됨).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import { authApi } from '../api/authApi';
import {
  MOCK_OCR_BUSINESS_NUMBER,
  MOCK_OCR_COMPANY_NAME,
  MOCK_OCR_REPRESENTATIVE_NAME,
  companyAuthHandlers,
} from '../mocks/companyAuth.mock';
import { CompanySignupPage } from './CompanySignupPage';

vi.mock('../hooks/useDaumPostcodeSearch', () => ({
  useDaumPostcodeSearch: () => ({
    openPostcodeSearch: (onComplete: (address: string) => void) => {
      onComplete('서울시 강남구 테헤란로 1');
    },
  }),
}));

const server = setupServer(...companyAuthHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
  vi.restoreAllMocks();
});
afterAll(() => server.close());

function renderPage() {
  const queryClient = new QueryClient();
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/signup/company']}>
        <CompanySignupPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function pngFile(name = 'license.png') {
  return new File(['dummy'], name, { type: 'image/png' });
}

function pdfFile(name = 'license.pdf') {
  return new File(['%PDF-1.4'], name, { type: 'application/pdf' });
}

describe('CompanySignupPage — 사업자등록증 OCR 자동채움(#587)', () => {
  it('PNG 업로드 시 OCR이 성공하면 사업자등록번호·상호명·대표자명이 자동으로 채워진다', async () => {
    renderPage();

    fireEvent.change(screen.getByLabelText('사업자등록증'), { target: { files: [pngFile()] } });

    await waitFor(() => {
      expect((screen.getByLabelText('사업자등록번호') as HTMLInputElement).value).toBe(
        MOCK_OCR_BUSINESS_NUMBER,
      );
    });
    expect((screen.getByLabelText('상호명') as HTMLInputElement).value).toBe(MOCK_OCR_COMPANY_NAME);
    expect((screen.getByLabelText('대표자명') as HTMLInputElement).value).toBe(
      MOCK_OCR_REPRESENTATIVE_NAME,
    );
  });

  it('OCR 호출 중 로딩 문구를 노출하고, 응답 후 사라진다', async () => {
    server.use(
      http.post('/api/auth/business-license/ocr', async () => {
        await new Promise((resolve) => setTimeout(resolve, 30));
        const success: ApiResponse<{
          businessRegistrationNumber: string | null;
          companyName: string | null;
          representativeName: string | null;
        }> = {
          success: true,
          data: {
            businessRegistrationNumber: MOCK_OCR_BUSINESS_NUMBER,
            companyName: MOCK_OCR_COMPANY_NAME,
            representativeName: MOCK_OCR_REPRESENTATIVE_NAME,
          },
        };
        return HttpResponse.json(success);
      }),
    );

    renderPage();
    fireEvent.change(screen.getByLabelText('사업자등록증'), { target: { files: [pngFile()] } });

    await waitFor(() => {
      expect(screen.getByRole('status')).not.toBeNull();
    });

    await waitFor(() => {
      expect(screen.queryByRole('status')).toBeNull();
    });
    expect((screen.getByLabelText('사업자등록번호') as HTMLInputElement).value).toBe(
      MOCK_OCR_BUSINESS_NUMBER,
    );
  });

  it('이미 입력된 필드는 OCR 결과로 덮어쓰지 않고, 빈 필드만 채운다', async () => {
    renderPage();

    fireEvent.change(screen.getByLabelText('사업자등록번호'), { target: { value: '9999999999' } });
    fireEvent.change(screen.getByLabelText('사업자등록증'), { target: { files: [pngFile()] } });

    await waitFor(() => {
      expect((screen.getByLabelText('상호명') as HTMLInputElement).value).toBe(MOCK_OCR_COMPANY_NAME);
    });
    // 사용자가 이미 입력한 사업자등록번호는 OCR 응답으로 덮어써지지 않는다.
    expect((screen.getByLabelText('사업자등록번호') as HTMLInputElement).value).toBe('9999999999');
  });

  it('OCR이 400으로 실패해도 에러 팝업 없이 조용히 폴백되고 수동 입력이 유지된다', async () => {
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

    renderPage();
    fireEvent.change(screen.getByLabelText('사업자등록증'), { target: { files: [pngFile()] } });

    // 로딩 문구가 사라질 때까지(요청이 끝날 때까지) 기다린 뒤에도 필드는 그대로 빈 값이어야 한다.
    await waitFor(() => {
      expect(screen.queryByRole('status')).toBeNull();
    });
    expect((screen.getByLabelText('사업자등록번호') as HTMLInputElement).value).toBe('');
    expect((screen.getByLabelText('상호명') as HTMLInputElement).value).toBe('');
    expect((screen.getByLabelText('대표자명') as HTMLInputElement).value).toBe('');

    // 수동 입력은 그대로 가능해야 한다(가입을 막지 않음).
    fireEvent.change(screen.getByLabelText('사업자등록번호'), { target: { value: '1112223334' } });
    expect((screen.getByLabelText('사업자등록번호') as HTMLInputElement).value).toBe('1112223334');
  });

  it('OCR이 네트워크 오류로 실패해도 조용히 폴백된다', async () => {
    server.use(
      http.post('/api/auth/business-license/ocr', () => {
        return HttpResponse.error();
      }),
    );

    renderPage();
    fireEvent.change(screen.getByLabelText('사업자등록증'), { target: { files: [pngFile()] } });

    await waitFor(() => {
      expect(screen.queryByRole('status')).toBeNull();
    });
    expect((screen.getByLabelText('사업자등록번호') as HTMLInputElement).value).toBe('');
  });

  it('PDF 업로드 시 OCR을 호출하지 않는다(백엔드 미지원)', async () => {
    const ocrSpy = vi.spyOn(authApi, 'businessLicenseOcr');

    renderPage();
    fireEvent.change(screen.getByLabelText('사업자등록증'), { target: { files: [pdfFile()] } });

    // 비동기 호출이 없다는 것을 확인하기 위해 한 틱 기다린다.
    await new Promise((resolve) => setTimeout(resolve, 10));

    expect(ocrSpy).not.toHaveBeenCalled();
    expect((screen.getByLabelText('사업자등록번호') as HTMLInputElement).value).toBe('');
    expect(screen.queryByRole('status')).toBeNull();
  });
});
