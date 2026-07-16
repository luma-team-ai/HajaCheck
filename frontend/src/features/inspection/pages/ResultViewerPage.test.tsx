// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { ApiResponse } from '../../../shared/api/types';
import { inspectionHandlers } from '../api/inspectionApi.handlers';
import type { InspectionResult } from '../types';
import { ResultViewerPage } from './ResultViewerPage';

const server = setupServer(...inspectionHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  // cleanup은 React Testing Library 자동 처리
});
afterAll(() => server.close());

function renderPage(path: string = '/inspections/1/viewer'): void {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/inspections/:id/viewer" element={<ResultViewerPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ResultViewerPage (통합 테스트)', () => {
  it('정상 렌더: 점검 결과 데이터(결함코드, 시설물명)를 로드해 표시한다', async () => {
    renderPage();

    // mock 데이터에서 실제 값 확인
    expect(await screen.findByText('DEF-0192')).not.toBeNull();
    expect(await screen.findByText('강남 오피스타워 A동')).not.toBeNull();
  });

  it('빈 데이터: 탐지된 하자가 없으면 해당 메시지를 표시한다', async () => {
    // 빈 defects 배열 응답으로 오버라이드
    server.use(
      http.get('/api/inspections/:id/result', () => {
        const emptyResult: ApiResponse<InspectionResult> = {
          success: true,
          data: {
            inspectionId: 1,
            media: {
              id: 1,
              imageUrl: 'data:image/svg+xml;utf8,...',
              width: 1600,
              height: 1200,
            },
            defectCode: 'DEF-TEST',
            facilityName: '테스트 시설',
            status: 'AI 검수중',
            reviewedCount: 10,
            totalCount: 100,
            defects: [], // 빈 배열 = 탐지된 하자가 없음
          },
        };
        return HttpResponse.json(emptyResult);
      }),
    );

    renderPage();
    expect(await screen.findByText('탐지된 하자가 없습니다.')).not.toBeNull();
  });
});
