// @vitest-environment jsdom
// DefectDetailPage 통합 테스트 — 하드코딩 목데이터 대신 useDefect(id) 실 데이터 조회로 바뀐 것을 검증(HAJA-30).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import { defectHandlers } from '../api/defectApi.handlers';
import { DefectDetailPage } from './DefectDetailPage';

const server = setupServer(...defectHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

function renderPage(id: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/defects/${id}`]}>
        <Routes>
          <Route path="/defects/:id" element={<DefectDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('DefectDetailPage (통합 테스트)', () => {
  it('실 데이터 조회 성공: 하자 정보(유형/등급/위치/상태)를 렌더링한다', async () => {
    renderPage('1');

    expect(await screen.findByText('철근 노출')).not.toBeNull();
    expect(screen.getByText('D · 경고')).not.toBeNull();
    expect(screen.getByText('강남 오피스타워 A동')).not.toBeNull();
    expect(screen.getByText('조치대기')).not.toBeNull();
  });

  it('존재하지 않는 id: 에러 폴백을 표시한다', async () => {
    renderPage('999999');

    expect(await screen.findByText('하자 정보를 불러오지 못했습니다.')).not.toBeNull();
  });

  it('서버 에러: 에러 폴백을 표시하고 재시도 버튼이 노출된다', async () => {
    server.use(
      http.get('/api/defects/:id', () => {
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: { code: 'INTERNAL_ERROR', message: '서버 오류가 발생했습니다.' },
        };
        return HttpResponse.json(failure, { status: 500 });
      }),
    );

    renderPage('1');

    expect(await screen.findByRole('button', { name: '다시 시도' })).not.toBeNull();
  });
});
