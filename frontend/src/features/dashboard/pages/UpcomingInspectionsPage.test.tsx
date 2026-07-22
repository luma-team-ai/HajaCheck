// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { HttpResponse, http } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { dashboardHandlers } from '../api/dashboardApi.handlers';
import { mockUpcomingInspections } from '../mocks/dashboard.mock';
import { UpcomingInspectionsPage } from './UpcomingInspectionsPage';

// 이 페이지는 처리대기·AI브리핑 위젯을 그대로 재사용하는데, 그 위젯들의 내용 자체는
// DashboardPage 쪽에서 이미 검증하므로 여기선 스텁으로 대체(DashboardPage.test.tsx와 동일 패턴).
vi.mock('../components/PendingPriorityCard', () => ({ PendingPriorityCard: () => <div>처리 대기</div> }));
vi.mock('../components/AiBriefingCard', () => ({ AiBriefingCard: () => <div>AI 주간 브리핑</div> }));

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
  it('목록·알림배너·재사용 위젯을 렌더링한다', async () => {
    renderPage();

    expect(await screen.findByText('한강대교 북단')).not.toBeNull();
    expect(screen.getByText('강남 오피스타워 A동')).not.toBeNull();
    expect(screen.getByText('판교 R&D 센터')).not.toBeNull();
    expect(
      screen.getByText(`다가오는 점검 일정이 ${mockUpcomingInspections.length}건 있습니다`),
    ).not.toBeNull();
    expect(screen.getByText('처리 대기')).not.toBeNull();
    expect(screen.getByText('AI 주간 브리핑')).not.toBeNull();
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
