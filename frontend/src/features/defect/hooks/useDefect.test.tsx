// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { setupServer } from 'msw/node';
import type { ReactNode } from 'react';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { defectHandlers } from '../api/defectApi.handlers';
import { useDefect } from './useDefect';

const server = setupServer(...defectHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

describe('useDefect', () => {
  it('존재하는 id로 호출하면 하자 상세를 반환한다', async () => {
    const { result } = renderHook(() => useDefect(1), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.id).toBe(1);
  });

  it('id가 undefined면 요청을 보내지 않는다(enabled: false)', () => {
    const { result } = renderHook(() => useDefect(undefined), { wrapper });

    expect(result.current.isPending).toBe(true);
    expect(result.current.fetchStatus).toBe('idle');
  });

  it('존재하지 않는 id면 isError가 true가 된다', async () => {
    const { result } = renderHook(() => useDefect(999999), { wrapper });

    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});
