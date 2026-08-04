// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { HttpResponse, http } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { dashboardHandlers } from '../api/dashboardApi.handlers';
import { mockUpcomingInspections } from '../mocks/dashboard.mock';
import { UpcomingInspectionsPage } from './UpcomingInspectionsPage';

const server = setupServer(...dashboardHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });

  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/dashboard/upcoming-inspections']}>
        <Routes>
          <Route path="/dashboard/upcoming-inspections" element={<UpcomingInspectionsPage />} />
          <Route path="/inspections/create" element={<div>점검 생성 화면</div>} />
          <Route path="/facilities/inspection-cycle" element={<div>점검 주기 화면</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('UpcomingInspectionsPage', () => {
  it('목록·알림배너를 렌더링한다', async () => {
    renderPage();

    expect(await screen.findByText('한강대교 북단')).not.toBeNull();
    expect(screen.getByText('강남 오피스타워 A동')).not.toBeNull();
    expect(screen.getByText('판교 R&D 센터')).not.toBeNull();
    expect(
      screen.getByText(`다가오는 점검 일정이 ${mockUpcomingInspections.length}건 있습니다`),
    ).not.toBeNull();
  });

  // #1568 — 목록 조회 기준(백엔드 기본값 DashboardController#getUpcomingInspections days=30,
  // limit=5)을 화면에 안내한다. InspectionCycleSettingsPage.tsx와 동일 문구(#1517).
  it('점검일 도래 시설물 제목에 조회 기준(D-30일 이하, 최대 5건)을 표시한다(#1568)', async () => {
    renderPage();

    expect(await screen.findByText('(D-30일 이하, 최대 5건)')).not.toBeNull();
  });

  // #1517 — 이 페이지 목적(점검일 도래 시설물 확인)과 무관한 "처리 대기" 위젯을 제거했다.
  it('"처리 대기" 위젯을 렌더링하지 않는다(#1517)', async () => {
    renderPage();

    await screen.findByText('한강대교 북단');
    expect(screen.queryByText('처리 대기')).toBeNull();
  });

  // #1568 — 이 페이지 목적(점검일 도래 시설물 확인)과 무관한 "AI 주간 브리핑" 위젯을 제거했다.
  it('"AI 주간 브리핑" 위젯을 렌더링하지 않는다(#1568)', async () => {
    renderPage();

    await screen.findByText('한강대교 북단');
    expect(screen.queryByText('AI 주간 브리핑')).toBeNull();
  });

  it('0건이면 알림배너 없이 빈 상태 문구를 표시한다', async () => {
    server.use(
      http.get('/api/dashboard/upcoming-inspections', () =>
        HttpResponse.json({ success: true, data: [] }),
      ),
    );

    renderPage();

    expect(await screen.findByText('다가오는 점검 일정이 없습니다.')).not.toBeNull();
    expect(screen.queryByText(/다가오는 점검 일정이 \d+건 있습니다/)).toBeNull();
  });

  it('"전체 스케줄 보기" 클릭 시 점검 주기 화면으로 이동한다', async () => {
    renderPage();
    await screen.findByText('한강대교 북단');

    fireEvent.click(screen.getByRole('button', { name: '전체 스케줄 보기' }));

    expect(await screen.findByText('점검 주기 화면')).not.toBeNull();
  });

  it('"새 점검 시작" 클릭 시 점검 생성 화면으로 이동한다', async () => {
    renderPage();
    await screen.findByText('한강대교 북단');

    fireEvent.click(screen.getByRole('button', { name: '+ 새 점검 시작' }));

    expect(await screen.findByText('점검 생성 화면')).not.toBeNull();
  });
});
