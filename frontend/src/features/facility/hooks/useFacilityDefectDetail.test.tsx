// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { setupServer } from 'msw/node';
import type { ReactNode } from 'react';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { facilityDefectHandlers } from '../api/facilityDefectApi.handlers';
import { useFacilityDefectDetail } from './useFacilityDefectDetail';

const server = setupServer(...facilityDefectHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function renderDetailHook(facilityId: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  return renderHook(() => useFacilityDefectDetail(facilityId), { wrapper });
}

describe('useFacilityDefectDetail', () => {
  it('목 데이터를 조회해 하자 상세를 반환한다', async () => {
    const { result } = renderDetailHook('detail');

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.defectType).toBe('균열');
    expect(result.current.data?.status).toBe('ACTION_PENDING');
  });
});