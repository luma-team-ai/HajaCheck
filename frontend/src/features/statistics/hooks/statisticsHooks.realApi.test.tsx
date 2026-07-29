// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import type { ReactNode } from 'react';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import { useMonthlyDefectTrend } from './useMonthlyDefectTrend';
import { useStatisticsGradeDistribution } from './useStatisticsGradeDistribution';

const server = setupServer();

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function renderStatisticsHook<T>(hook: () => T) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  return renderHook(hook, { wrapper });
}

describe('statistics hooks real API behavior', () => {
  it('빈 배열 응답을 mock 통계 숫자로 대체하지 않는다', async () => {
    server.use(
      http.get('/api/statistics/monthly-trend', () =>
        HttpResponse.json({ success: true, data: [] } satisfies ApiResponse<[]>),
      ),
    );

    const { result } = renderStatisticsHook(() => useMonthlyDefectTrend({ period: '6m', facilityId: 'all' }));

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(result.current.data).toEqual([]);
  });

  it('404 응답을 mock 등급 분포로 대체하지 않고 오류로 유지한다', async () => {
    server.use(
      http.get('/api/statistics/grade-distribution', () =>
        HttpResponse.json(
          { success: false, data: null, error: { code: 'RESOURCE_NOT_FOUND', message: 'not found' } },
          { status: 404 },
        ),
      ),
    );

    const { result } = renderStatisticsHook(() => useStatisticsGradeDistribution({ period: '6m' }));

    await waitFor(() => expect(result.current.isError).toBe(true));

    expect(result.current.data).toBeUndefined();
  });
});
