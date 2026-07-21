// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { setupServer } from 'msw/node';
import type { ReactNode } from 'react';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { facilityComparisonHandlers } from '../api/facilityComparisonApi.handlers';
import { useFacilityComparison } from './useFacilityComparison';

const server = setupServer(...facilityComparisonHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function renderComparisonHook(facilityId: string, beforeCycle: number, afterCycle: number) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  return renderHook(() => useFacilityComparison(facilityId, beforeCycle, afterCycle), { wrapper });
}

describe('useFacilityComparison', () => {
  it('목 비교 데이터를 조회해 KPI·추이·변화 목록을 반환한다', async () => {
    const { result } = renderComparisonHook('detail', 7, 8);

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.beforeCycle.cycle).toBe(7);
    expect(result.current.data?.afterCycle.cycle).toBe(8);
    expect(result.current.data?.kpis).toHaveLength(4);
    expect(result.current.data?.changes.length).toBeGreaterThan(0);
  });
});