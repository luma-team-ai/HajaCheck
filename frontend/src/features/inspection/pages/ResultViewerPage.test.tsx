// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
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
  // vitest globals 미설정 환경이라 RTL 자동 cleanup이 안 걸림 — 명시 호출 필요
  cleanup();
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

  it('선택된 하자가 필터로 제외되면 목록의 첫 항목으로 자동 대체된다', async () => {
    renderPage();
    await screen.findByText('DEF-0192');

    // id=2(박리박락, confidence 0.81)를 선택
    fireEvent.click(screen.getByTitle(/박리박락/));
    expect(await screen.findByText('콘크리트 표면 박리 영역 확대 중. 즉시 조치 필요.')).not.toBeNull();

    // 신뢰도 threshold를 0.9로 올려 id=2를 필터에서 제외
    fireEvent.change(screen.getByRole('slider'), { target: { value: '0.9' } });

    // 선택이 남아있는 첫 항목(id=1, 균열)으로 자동 대체된다
    expect(
      await screen.findByText('수평 방향의 구조적 균열로 판단됨. 보수 권장.'),
    ).not.toBeNull();
    expect(screen.queryByText('콘크리트 표면 박리 영역 확대 중. 즉시 조치 필요.')).toBeNull();
  });

  it('필터 결과가 0건이면(원본 데이터는 있음) 안내 메시지를 표시한다(#368)', async () => {
    renderPage();
    await screen.findByText('DEF-0192');

    // 신뢰도 threshold를 최대로 올려 모든 하자(최고 confidence 0.98)를 필터에서 제외
    fireEvent.change(screen.getByRole('slider'), { target: { value: '1' } });

    expect(await screen.findByText('조건에 맞는 하자가 없습니다.')).not.toBeNull();
  });

  it('"검수 확정" 버튼은 백엔드 미구현으로 비활성화되어 있다(#368, #16/#17 완료 시 활성화)', async () => {
    renderPage();
    await screen.findByText('DEF-0192');

    const button = screen.getByRole('button', { name: '이 이미지 검수 확정' });
    expect(button.hasAttribute('disabled')).toBe(true);
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
