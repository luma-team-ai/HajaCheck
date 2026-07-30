// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import type { ReactNode } from 'react';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { menuHandlers } from '../api/menuApi.handlers';
import { mockMenuTree } from '../mocks/menu.mock';
import { useMenuTree } from './useMenuTree';

const server = setupServer(...menuHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

describe('useMenuTree', () => {
  it('role 기준으로 필터링된 메뉴 트리를 반환한다', async () => {
    const { result } = renderHook(() => useMenuTree(), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(result.current.data).toHaveLength(mockMenuTree.length);
    expect(result.current.data?.[0].code).toBe('DASHBOARD');
  });

  it('요청 실패 시 isError가 true가 된다(SideNavBar가 자체 기본값으로 폴백할 수 있도록)', async () => {
    server.use(
      http.get('/api/menus', () =>
        HttpResponse.json({ success: false, error: { code: 'UNKNOWN_ERROR', message: '실패' } }),
      ),
    );

    const { result } = renderHook(() => useMenuTree(), { wrapper });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.data).toBeUndefined();
  });
});
