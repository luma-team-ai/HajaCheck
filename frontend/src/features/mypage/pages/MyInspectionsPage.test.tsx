// @vitest-environment jsdom
// MyInspectionsPage(마이페이지 내 점검 이력 / 보고서, HAJA-366 #668) 통합 테스트 — 실제
// useMyInspectionsSummary/useMyInspections/useMyReports 훅 + MSW mypageHandlers를 통해
// KPI·테이블·탭 전환·보고서 카드·페이지네이션 렌더를 검증한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { mypageHandlers } from '../api/mypageApi.handlers';
import { MyInspectionsPage } from './MyInspectionsPage';

const server = setupServer(...mypageHandlers);

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
    <QueryClientProvider client={queryClient}>
      <MyInspectionsPage />
    </QueryClientProvider>,
  );
}

describe('MyInspectionsPage', () => {
  it('헤더(제목·부제)와 KPI 4종을 렌더링한다', async () => {
    renderPage();

    expect(await screen.findByRole('heading', { name: '내 점검 이력 / 보고서' })).toBeTruthy();
    expect(screen.getByText('내가 참여한 점검 회차와 발급한 보고서를 확인하세요.')).toBeTruthy();

    expect(await screen.findByText('18회차')).toBeTruthy(); // 참여 점검
    expect(screen.getByText('참여 점검')).toBeTruthy();
    expect(screen.getByText('검수 확정')).toBeTruthy();
    expect(screen.getByText('발급 보고서')).toBeTruthy();
    expect(screen.getByText('진행 중')).toBeTruthy();
  });

  it('기본 활성 탭(점검 이력)에서 테이블 행·역할 배지·상태 배지·페이지네이션을 렌더링한다', async () => {
    renderPage();

    expect(await screen.findByText('강남 오피스타워 A동')).toBeTruthy();
    expect(screen.getByText('성수동 지식산업센터 1차')).toBeTruthy();

    // 역할 배지(점검자/소유자)
    const firstRow = screen.getByText('강남 오피스타워 A동').closest('tr') as HTMLElement;
    expect(within(firstRow).getByText('점검자')).toBeTruthy();
    expect(within(firstRow).getByText('검수완료')).toBeTruthy();
    expect(within(firstRow).getByText('결과 보기')).toBeTruthy();

    const ownerRow = screen.getByText('성수동 지식산업센터 1차').closest('tr') as HTMLElement;
    expect(within(ownerRow).getByText('소유자')).toBeTruthy();
    expect(within(ownerRow).getByText('검수대기')).toBeTruthy();

    // 페이지네이션 — mock totalElements=18, pageSize=8 → "1-8 / 18"
    expect(screen.getByText('1-8 / 18')).toBeTruthy();
  });

  it("'내 보고서' 탭으로 전환하면 테이블 대신 보고서 카드 목록을 렌더링한다", async () => {
    renderPage();

    await screen.findByText('강남 오피스타워 A동');

    fireEvent.click(screen.getByRole('tab', { name: '내 보고서' }));

    expect(screen.queryByText('강남 오피스타워 A동')).toBeNull();
    expect(await screen.findByText('최근 발급된 보고서')).toBeTruthy();
    expect(screen.getByText('[24-03] 강남 오피스타워 A동 정밀점검 보고서')).toBeTruthy();
    expect(screen.getByText('2024.03.16 · 1.2MB')).toBeTruthy();

    const downloadButtons = screen.getAllByRole('button', { name: /다운로드/ });
    expect(downloadButtons.length).toBe(3);
    downloadButtons.forEach((button) => expect(button).toHaveProperty('disabled', true));
  });

  it("'점검 이력' 탭으로 되돌아가면 테이블이 다시 보인다", async () => {
    renderPage();

    await screen.findByText('강남 오피스타워 A동');
    fireEvent.click(screen.getByRole('tab', { name: '내 보고서' }));
    await screen.findByText('최근 발급된 보고서');

    fireEvent.click(screen.getByRole('tab', { name: '점검 이력' }));

    expect(await screen.findByText('강남 오피스타워 A동')).toBeTruthy();
    expect(screen.queryByText('최근 발급된 보고서')).toBeNull();
  });
});
