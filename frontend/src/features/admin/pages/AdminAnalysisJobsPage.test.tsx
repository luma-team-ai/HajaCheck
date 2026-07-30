// @vitest-environment jsdom
// AdminAnalysisJobsPage 통합 테스트 — 실제 useAdminAnalysisJobs 훅 + MSW
// adminAnalysisJobHandlers를 통해 목록 렌더·상태 필터·배지·진행률 표시를 검증한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { MemoryRouter } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { adminAnalysisJobHandlers } from '../api/adminAnalysisJobApi.handlers';
import { AdminAnalysisJobsPage } from './AdminAnalysisJobsPage';

const server = setupServer(...adminAnalysisJobHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

function renderPage(): void {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });

  render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <AdminAnalysisJobsPage />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe('AdminAnalysisJobsPage (통합 테스트)', () => {
  it('목록을 불러와 시설물명·담당 검사자·점검ID를 렌더링한다', async () => {
    renderPage();

    expect(await screen.findByText('강남 오피스빌딩')).toBeTruthy();
    // 김지수는 강남 오피스빌딩·판교 데이터센터 두 건 모두의 담당 검사자라 두 행에 나타난다.
    expect(screen.getAllByText('김지수').length).toBe(2);
    expect(screen.getByText('#101')).toBeTruthy();
  });

  it('진행중 건에는 진행률이, 완료 건에는 -가 표시된다', async () => {
    renderPage();

    await screen.findByText('강남 오피스빌딩');
    expect(screen.getByText('42%')).toBeTruthy();
    // 완료 건(역삼 지식산업센터)의 진행률 셀은 '-'
    const completedRow = screen.getByText('역삼 지식산업센터').closest('tr');
    expect(completedRow?.textContent).toContain('-');
  });

  it('상태 필터를 "진행중"으로 바꾸면 ANALYZING 건만 남는다', async () => {
    renderPage();

    await screen.findByText('역삼 지식산업센터'); // 필터 전 초기 목록이 뜰 때까지 대기
    fireEvent.click(screen.getByRole('tab', { name: '진행중' }));

    await waitFor(() => expect(screen.queryByText('역삼 지식산업센터')).toBeNull());
    expect(screen.getByText('강남 오피스빌딩')).toBeTruthy();
    expect(screen.queryByText('서초 주상복합')).toBeNull();
  });

  it('상태 필터를 "대기"로 바꾸면 PENDING 건만 남는다', async () => {
    renderPage();

    await screen.findByText('강남 오피스빌딩'); // 필터 전 초기 목록이 뜰 때까지 대기
    fireEvent.click(screen.getByRole('tab', { name: '대기' }));

    await waitFor(() => expect(screen.queryByText('강남 오피스빌딩')).toBeNull());
    expect(screen.getByText('서초 주상복합')).toBeTruthy();
  });
});
