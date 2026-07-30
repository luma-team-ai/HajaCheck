// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import type { ReactNode } from 'react';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import { facilityDefectHandlers } from '../api/facilityDefectApi.handlers';
import { mockFacilityDefectDetailResponse } from '../mocks/facilityDefect.mock';
import type { FacilityDefectDetailResponse } from '../types';
import { useFacilityDefectDetail } from './useFacilityDefectDetail';

const server = setupServer(...facilityDefectHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function renderDetailHook(defectId: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  return renderHook(() => useFacilityDefectDetail(defectId), { wrapper });
}

describe('useFacilityDefectDetail', () => {
  it('GET /api/defects/{defectId} 응답을 화면용 FacilityDefectDetail로 매핑해 반환한다', async () => {
    const { result } = renderDetailHook('101');

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.defectType).toBe('균열'); // ← typeLabel
    expect(result.current.data?.status).toBe('CONFIRMED');
    expect(result.current.data?.location).toBe('외벽 동측 12층 부근');
    expect(result.current.data?.assigneeName).toBe('김검수');
    expect(result.current.data?.foundAt).toBe('2026-06-21'); // ← createdAt(ISO) 절삭
  });

  it('confidence(0~1)를 confidencePercent(0~100)로, crackLengthMm(mm)를 lengthM(m)으로 변환한다', async () => {
    const { result } = renderDetailHook('101');

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.confidencePercent).toBe(94); // 0.94 * 100
    expect(result.current.data?.widthMm).toBe(0.8); // crackWidthMm 그대로
    expect(result.current.data?.lengthM).toBe(2.4); // crackLengthMm(2400mm) ÷ 1000
  });

  it('균열이 아닌 유형(crackWidthMm/crackLengthMm null) 또는 미배정 담당자는 null을 그대로 유지한다', async () => {
    const nonCrackResponse: FacilityDefectDetailResponse = {
      ...mockFacilityDefectDetailResponse,
      crackWidthMm: null,
      crackLengthMm: null,
      location: null,
      assigneeName: null,
      grade: null,
    };
    server.use(
      http.get('/api/defects/:id', () => {
        const body: ApiResponse<FacilityDefectDetailResponse> = {
          success: true,
          data: nonCrackResponse,
        };
        return HttpResponse.json(body);
      }),
    );

    const { result } = renderDetailHook('102');

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.widthMm).toBeNull();
    expect(result.current.data?.lengthM).toBeNull();
    expect(result.current.data?.location).toBeNull();
    expect(result.current.data?.assigneeName).toBeNull();
    expect(result.current.data?.grade).toBeNull();
  });
});
