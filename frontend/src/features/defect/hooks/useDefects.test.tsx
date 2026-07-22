// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import type { ReactNode } from 'react';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse, PageResponse } from '../../../shared/api/types';
import { defectHandlers } from '../api/defectApi.handlers';
import { mockDefects } from '../mocks/defect.mock';
import type { Defect } from '../types';
import { useDefects } from './useDefects';

const server = setupServer(...defectHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

describe('useDefects', () => {
  it('필터 없이 호출하면 전체 하자 목록을 반환한다', async () => {
    const { result } = renderHook(() => useDefects(), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(result.current.data?.content).toHaveLength(mockDefects.length);
    expect(result.current.data?.totalElements).toBe(mockDefects.length);
  });

  it('필터를 전달하면 쿼리 파라미터로 서버에 전달된다', async () => {
    server.use(
      http.get('/api/defects', ({ request }) => {
        const url = new URL(request.url);
        const body: ApiResponse<PageResponse<Defect>> = {
          success: true,
          data: {
            content: url.searchParams.get('grade') === 'E' ? [mockDefects[0]] : [],
            page: 0,
            totalElements: url.searchParams.get('grade') === 'E' ? 1 : 0,
          },
        };
        return HttpResponse.json(body);
      }),
    );

    const { result } = renderHook(() => useDefects({ grade: 'E' }), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.totalElements).toBe(1);
  });

  it('요청 실패 시 isError가 true가 된다', async () => {
    server.use(
      http.get('/api/defects', () => {
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: { code: 'INTERNAL_ERROR', message: '서버 오류가 발생했습니다.' },
        };
        return HttpResponse.json(failure, { status: 500 });
      }),
    );

    const { result } = renderHook(() => useDefects(), { wrapper });

    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});
