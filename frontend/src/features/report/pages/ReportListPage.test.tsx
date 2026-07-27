// @vitest-environment jsdom
// ReportListPage(보고서 목록 / 이력 관리, #463) 통합 테스트 — 실제 useCompanyReports/
// useCompanyReportsSummary 훅 + MSW reportHandlers/facilityHandlers를 통해 KPI·테이블·필터·
// 버전 이력 패널 렌더를 검증한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { facilityHandlers } from '../../facility/api/facilityApi.handlers';
import { reportHandlers } from '../api/reportApi.handlers';
import { platformAdminCompanyHandlers } from '../../platform-admin/api/platformAdminCompanyApi.handlers';
import { formatReportListTitle } from '../utils/reportListFormat';
import { ReportListPage } from './ReportListPage';

// 목 데이터(mocks/reportList.mock.ts) 101/103번 항목과 1:1 대응 — title은 서버 필드가 아니라
// facilityName+roundNo+updatedAt으로 조립되므로(reportListFormat.ts) 테스트도 동일하게 조립해 비교한다.
const REPORT_101_TITLE = formatReportListTitle('판교 테크원타워', '2026-07-24T14:30:00', 3);
const REPORT_103_TITLE = formatReportListTitle('강남 파이낸스센터', '2026-06-23T09:15:00', 1);

const server = setupServer(...reportHandlers, ...facilityHandlers, ...platformAdminCompanyHandlers);

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
      <MemoryRouter>
        <ReportListPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ReportListPage', () => {
  it('헤더·KPI 4종을 렌더링한다', async () => {
    renderPage();

    expect(await screen.findByRole('heading', { name: '보고서' })).toBeTruthy();
    expect(await screen.findByText('전체')).toBeTruthy();
    expect(screen.getAllByText('완료').length).toBeGreaterThan(0);
    expect(screen.getAllByText('편집 중').length).toBeGreaterThan(0);
    expect(screen.getByText('이번 달 발급')).toBeTruthy();
  });

  it('목록 행(보고서명·시설물·상태·버전)을 렌더링한다', async () => {
    renderPage();

    expect(await screen.findByText(REPORT_101_TITLE)).toBeTruthy();
    const warnings = screen.getAllByRole('img', { name: 'AI 초안 주의 및 법적 고지' });
    expect(warnings.length).toBeGreaterThan(0);
    expect(warnings[0].getAttribute('title')).toBe(
      '본 보고서는 점검 데이터 기반 AI가 작성한 초안입니다. 법정 제출 및 실무 활용 전 담당 검수자의 내용 확인 및 최종 확정(Finalize) 절차가 필수입니다.',
    );
    const row = screen.getByText(REPORT_101_TITLE).closest('tr') as HTMLElement;
    expect(within(row).getByText('판교 테크원타워')).toBeTruthy();
    expect(within(row).getByText('완료')).toBeTruthy();
    expect(within(row).getByText('v3')).toBeTruthy();
  });

  it('실 API가 200 빈 페이지를 반환하면 목 목록으로 바꾸지 않는다', async () => {
    server.use(
      http.get('/api/reports', () =>
        HttpResponse.json({
          success: true,
          data: { content: [], page: 0, totalElements: 0 },
        }),
      ),
    );

    renderPage();

    expect(await screen.findByText('조회된 보고서가 없습니다')).toBeTruthy();
    expect(screen.queryByText(REPORT_101_TITLE)).toBeNull();
  });

  it('시설물 필터 적용 시 다른 시설물 보고서가 사라진다', async () => {
    renderPage();

    expect(await screen.findByText(REPORT_101_TITLE)).toBeTruthy();
    expect(screen.getByText(REPORT_103_TITLE)).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '시설물 필터' }));
    fireEvent.click(await screen.findByRole('option', { name: '강남 오피스타워 A동' }));

    await screen.findByText(REPORT_101_TITLE);
    expect(screen.queryByText(REPORT_103_TITLE)).toBeNull();
  });

  it('페이지를 넘겨도 이전 페이지에서 선택한 완료 보고서를 일괄 내보내기 대상으로 유지한다', async () => {
    renderPage();

    const firstRow = (await screen.findByText(REPORT_101_TITLE)).closest('tr') as HTMLElement;
    fireEvent.click(within(firstRow).getByRole('checkbox', { name: `${REPORT_101_TITLE} 선택` }));
    expect(screen.getByRole('button', { name: /내보내기\(일괄\)/ }).textContent).toContain('(1)');

    fireEvent.click(screen.getByRole('button', { name: '다음 페이지' }));
    expect(await screen.findByText(/\[26-03\] 수원 스마트팩토리/)).toBeTruthy();
    expect(screen.getByRole('button', { name: /내보내기\(일괄\)/ }).textContent).toContain('(1)');
  });

  // NOTES.md §2.2 "[WHEN: 보고서 목록/이력 관리 개발 시] MUST: 행 클릭 시 버전 이력 플라이아웃" —
  // ⋮ 메뉴 경유가 아니라 행 자체 클릭으로 열려야 하는 PRD 필수 요구사항을 고정하는 테스트.
  it('행을 클릭하면 우측 패널에 해당 보고서의 버전 이력이 표시된다', async () => {
    renderPage();

    expect(
      screen.getByText('행의 ⋮ 메뉴에서 "버전 이력"을 선택하면 여기에 보고서 버전 목록이 표시됩니다.'),
    ).toBeTruthy();

    const row = (await screen.findByText(REPORT_101_TITLE)).closest('tr') as HTMLElement;
    fireEvent.click(row);

    expect(await screen.findAllByText(/v\d+/)).not.toHaveLength(0);
  });

  it('행 ⋮ 메뉴의 "버전 이력" 항목으로도 동일하게 패널을 열 수 있다', async () => {
    renderPage();

    const row = (await screen.findByText(REPORT_101_TITLE)).closest('tr') as HTMLElement;
    fireEvent.click(within(row).getByRole('button', { name: /작업 메뉴 열기/ }));
    fireEvent.click(await screen.findByRole('menuitem', { name: '버전 이력' }));

    expect(await screen.findAllByText(/v\d+/)).not.toHaveLength(0);
  });
});
