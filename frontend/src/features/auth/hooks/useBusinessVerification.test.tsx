// @vitest-environment jsdom
// 사업자 진위확인(#648 BE, #663 FE) — useEmailAvailability와 동일 골격의 React Query mutation 훅.
// result 6종 분기 + 400/429 에러가 ApiError로 그대로 전달되는지 검증한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import type { ReactNode } from 'react';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import {
  MOCK_VERIFIED_BUSINESS_NUMBER,
  MOCK_VERIFIED_BUSINESS_START_DATE,
  MOCK_VERIFIED_MESSAGE,
  MOCK_VERIFIED_REPRESENTATIVE_NAME,
  companyAuthHandlers,
} from '../mocks/companyAuth.mock';
import type { BusinessVerificationResponse } from '../types';
import { useBusinessVerification } from './useBusinessVerification';

const server = setupServer(...companyAuthHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function renderHarness() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  return renderHook(() => useBusinessVerification(), { wrapper });
}

function respondWith(result: BusinessVerificationResponse['result'], message: string) {
  server.use(
    http.post('/api/auth/business-verification', () => {
      const success: ApiResponse<BusinessVerificationResponse> = {
        success: true,
        data: { result, message },
      };
      return HttpResponse.json(success);
    }),
  );
}

const VALID_REQUEST = {
  businessRegistrationNumber: MOCK_VERIFIED_BUSINESS_NUMBER,
  representativeName: MOCK_VERIFIED_REPRESENTATIVE_NAME,
  businessStartDate: MOCK_VERIFIED_BUSINESS_START_DATE,
};

describe('useBusinessVerification', () => {
  it('일치하는 사업자 정보로 확인하면 VERIFIED 결과를 반환한다', async () => {
    const { result } = renderHarness();

    result.current.verify(VALID_REQUEST);

    await waitFor(() => expect(result.current.result).not.toBeUndefined());
    expect(result.current.result).toEqual({ result: 'VERIFIED', message: MOCK_VERIFIED_MESSAGE });
    expect(result.current.error).toBeNull();
  });

  it.each([
    ['NOT_REGISTERED', '국세청에 등록되지 않은 사업자입니다.'],
    ['MISMATCH', '사업자번호는 존재하나 대표자명 또는 개업일자가 일치하지 않습니다.'],
    ['SUSPENDED', '휴업 중인 사업자입니다.'],
    ['CLOSED', '폐업한 사업자입니다.'],
    ['UNAVAILABLE', '국세청 서비스 장애로 확인이 지연되고 있습니다. 잠시 후 다시 시도해 주세요.'],
  ] as const)('%s 결과를 그대로 반환한다', async (resultCode, message) => {
    respondWith(resultCode, message);
    const { result } = renderHarness();

    result.current.verify(VALID_REQUEST);

    await waitFor(() => expect(result.current.result).not.toBeUndefined());
    expect(result.current.result).toEqual({ result: resultCode, message });
  });

  it('400 INVALID_INPUT 응답은 ApiError로 전달된다', async () => {
    server.use(
      http.post('/api/auth/business-verification', () => {
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: { code: 'INVALID_INPUT', message: '입력값을 다시 확인해 주세요.' },
        };
        return HttpResponse.json(failure, { status: 400 });
      }),
    );
    const { result } = renderHarness();

    result.current.verify(VALID_REQUEST);

    await waitFor(() => expect(result.current.error).not.toBeNull());
    expect(result.current.error).toMatchObject({ code: 'INVALID_INPUT' });
    expect(result.current.result).toBeUndefined();
  });

  it('429 AUTH_TOO_MANY_REQUESTS 응답은 ApiError로 전달된다(rate limit)', async () => {
    server.use(
      http.post('/api/auth/business-verification', () => {
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: { code: 'AUTH_TOO_MANY_REQUESTS', message: '잠시 후 다시 시도해 주세요.' },
        };
        return HttpResponse.json(failure, { status: 429 });
      }),
    );
    const { result } = renderHarness();

    result.current.verify(VALID_REQUEST);

    await waitFor(() => expect(result.current.error).not.toBeNull());
    expect(result.current.error).toMatchObject({ code: 'AUTH_TOO_MANY_REQUESTS' });
  });

  it('reset을 호출하면 이전 결과가 지워진다(무효화)', async () => {
    const { result } = renderHarness();

    result.current.verify(VALID_REQUEST);
    await waitFor(() => expect(result.current.result).not.toBeUndefined());

    result.current.reset();
    await waitFor(() => expect(result.current.result).toBeUndefined());
  });
});
