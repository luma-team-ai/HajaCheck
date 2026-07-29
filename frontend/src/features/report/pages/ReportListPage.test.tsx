// @vitest-environment jsdom
// ReportListPage(보고서 목록 / 이력 관리, #463) 통합 테스트 — 실제 useCompanyReports/
// useCompanyReportsSummary 훅 + MSW reportHandlers/facilityHandlers를 통해 KPI·테이블·필터·
// 버전 이력 패널 렌더를 검증한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { facilityHandlers } from '../../facility/api/facilityApi.handlers';
import { reportHandlers } from '../api/reportApi.handlers';
import { platformAdminCompanyHandlers } from '../../platform-admin/api/platformAdminCompanyApi.handlers';
import { formatReportListTitle } from '../utils/reportListFormat';
import { ReportListPage } from './ReportListPage';
import { AI_DRAFT_WARNING, AI_DRAFT_WARNING_TITLE } from '../constants';

const navigateMock = vi.fn();

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

vi.mock('../utils/exportReportToPdf', async () => {
  const actual = await vi.importActual<typeof import('../utils/exportReportToPdf')>('../utils/exportReportToPdf');
  return { ...actual, exportReportToPdf: vi.fn().mockResolvedValue(new Blob(['%PDF-1.4 test'])) };
});

// 목 데이터(mocks/reportList.mock.ts) 101/103번 항목과 1:1 대응 — title은 서버 필드가 아니라
// facilityName+roundNo+updatedAt으로 조립되므로(reportListFormat.ts) 테스트도 동일하게 조립해 비교한다.
const REPORT_101_TITLE = formatReportListTitle('판교 테크원타워', '2026-07-24T14:30:00', 3);
const REPORT_103_TITLE = formatReportListTitle('강남 파이낸스센터', '2026-06-23T09:15:00', 1);
const reportContent = {
  overview: { purpose: '정기점검', facility_summary: '시설물 개요', scope: '공용부' },
  summary: { overall_opinion: '양호', total_count: 0, count_by_grade: {}, key_findings: [] },
  detail: { items: [] },
  recommendation: { items: [], monitoring_points: [] },
};

const server = setupServer(...reportHandlers, ...facilityHandlers, ...platformAdminCompanyHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  navigateMock.mockClear();
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
    const warnings = screen.getAllByRole('img', { name: AI_DRAFT_WARNING_TITLE });
    expect(warnings.length).toBeGreaterThan(0);
    expect(warnings[0].getAttribute('title')).toBe(AI_DRAFT_WARNING);
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

  // NOTES.md §2.2 "[WHEN: 보고서 목록/이력 관리 개발 시] MUST: 행 클릭 시 변경 이력 플라이아웃" —
  // ⋮ 메뉴 경유가 아니라 행 자체 클릭으로 열려야 하는 PRD 필수 요구사항을 고정하는 테스트.
  it('행을 클릭하면 우측 패널에 해당 보고서의 변경 이력이 표시된다', async () => {
    renderPage();

    expect(
      screen.getByText('행의 ⋮ 메뉴에서 "변경 이력"을 선택하면 여기에 보고서 버전 목록이 표시됩니다.'),
    ).toBeTruthy();

    const row = (await screen.findByText(REPORT_101_TITLE)).closest('tr') as HTMLElement;
    fireEvent.click(row);

    expect(await screen.findAllByText(/v\d+/)).not.toHaveLength(0);
  });

  it('행 ⋮ 메뉴의 "변경 이력" 항목으로도 동일하게 패널을 열 수 있다', async () => {
    renderPage();

    const row = (await screen.findByText(REPORT_101_TITLE)).closest('tr') as HTMLElement;
    fireEvent.click(within(row).getByRole('button', { name: /작업 메뉴 열기/ }));
    fireEvent.click(await screen.findByRole('menuitem', { name: '변경 이력' }));

    expect(await screen.findAllByText(/v\d+/)).not.toHaveLength(0);
  });

  it('행 메뉴에서 복사하면 API 호출 후 새 초안 편집 화면으로 이동한다', async () => {
    renderPage();

    const row = (await screen.findByText(REPORT_103_TITLE)).closest('tr') as HTMLElement;
    fireEvent.click(within(row).getByRole('button', { name: /작업 메뉴 열기/ }));
    fireEvent.click(await screen.findByRole('menuitem', { name: '복사' }));

    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/reports/1103'));
  });

  it('DRAFT 행 제출 처리는 상세조회_재검증_PDF업로드_확정 순서로 실행한다', async () => {
    const calls: string[] = [];
    server.use(
      http.get('/api/reports/103', () => {
        calls.push('detail');
        return HttpResponse.json({
          success: true,
          data: {
            id: 103,
            inspectionId: 3,
            version: 3,
            status: 'DRAFT',
            groundingCheckPassed: null,
            content: reportContent,
            createdBy: 1,
            createdAt: '2026-06-23T09:15:00',
          },
        });
      }),
      http.post('/api/reports/103/grounding-recheck', () => {
        calls.push('recheck');
        return HttpResponse.json({
          success: true,
          data: {
            id: 103,
            inspectionId: 3,
            version: 3,
            status: 'DRAFT',
            groundingCheckPassed: true,
            content: reportContent,
            createdBy: 1,
            createdAt: '2026-06-23T09:15:00',
          },
        });
      }),
      http.get('/api/inspections/3/defects', () => {
        calls.push('defects');
        return HttpResponse.json({ success: true, data: [] });
      }),
      http.post('/api/reports/103/pdf', () => {
        calls.push('upload');
        return HttpResponse.json({ success: true, data: { pdfUrl: '/api/reports/103/pdf/generated.pdf' } });
      }),
      http.post('/api/reports/103/finalize', async ({ request }) => {
        calls.push('finalize');
        const body = (await request.json()) as { pdfUrl: string };
        expect(body.pdfUrl).toBe('/api/reports/103/pdf/generated.pdf');
        return HttpResponse.json({
          success: true,
          data: {
            id: 103,
            inspectionId: 3,
            version: 3,
            status: 'FINALIZED',
            groundingCheckPassed: true,
            pdfUrl: body.pdfUrl,
            content: reportContent,
            createdBy: 1,
            createdAt: '2026-06-23T09:15:00',
          },
        });
      }),
    );

    renderPage();

    const row = (await screen.findByText(REPORT_103_TITLE)).closest('tr') as HTMLElement;
    fireEvent.click(within(row).getByRole('button', { name: /작업 메뉴 열기/ }));
    fireEvent.click(await screen.findByRole('menuitem', { name: '발행' }));

    await waitFor(() => expect(calls).toEqual(['detail', 'recheck', 'defects', 'upload', 'finalize']));
  });

  it('FINALIZED 행의 제출 처리는 disabled다', async () => {
    renderPage();

    const row = (await screen.findByText(REPORT_101_TITLE)).closest('tr') as HTMLElement;
    fireEvent.click(within(row).getByRole('button', { name: /작업 메뉴 열기/ }));

    expect((await screen.findByRole('menuitem', { name: '발행' })).hasAttribute('disabled')).toBe(true);
  });

  it('DRAFT 행 삭제는 DELETE 호출 후 목록에서 해당 보고서를 제외한다', async () => {
    let deleted = false;
    server.use(
      http.delete('/api/reports/103', () => {
        deleted = true;
        return HttpResponse.json({ success: true, data: null });
      }),
      http.get('/api/reports', () => {
        const content = deleted
          ? []
          : [
              {
                id: 103,
                inspectionId: 3,
                facilityId: 4,
                facilityName: '강남 파이낸스센터',
                roundNo: 1,
                gradeDistribution: { B: 1, C: 12 },
                status: 'DRAFT',
                version: 3,
                updatedAt: '2026-06-23T09:15:00',
                pdfUrl: null,
              },
            ];
        return HttpResponse.json({
          success: true,
          data: { content, page: 0, totalElements: content.length },
        });
      }),
    );

    renderPage();

    const row = (await screen.findByText(REPORT_103_TITLE)).closest('tr') as HTMLElement;
    fireEvent.click(within(row).getByRole('button', { name: /작업 메뉴 열기/ }));
    fireEvent.click(await screen.findByRole('menuitem', { name: '삭제' }));

    // 공용 Modal 확인 버튼 클릭
    fireEvent.click(await screen.findByRole('button', { name: '삭제' }));

    await waitFor(() => expect(screen.queryByText(REPORT_103_TITLE)).toBeNull());
    expect(deleted).toBe(true);
  });

  it('DRAFT 행 삭제를 확인창에서 취소하면 DELETE를 호출하지 않는다', async () => {
    let deleteCount = 0;
    server.use(
      http.delete('/api/reports/103', () => {
        deleteCount += 1;
        return HttpResponse.json({ success: true, data: null });
      }),
    );

    renderPage();

    const row = (await screen.findByText(REPORT_103_TITLE)).closest('tr') as HTMLElement;
    fireEvent.click(within(row).getByRole('button', { name: /작업 메뉴 열기/ }));
    fireEvent.click(await screen.findByRole('menuitem', { name: '삭제' }));

    // 공용 Modal 취소 버튼 클릭 → DELETE 미호출
    fireEvent.click(await screen.findByRole('button', { name: '취소' }));

    expect(deleteCount).toBe(0);
    expect(screen.getByText(REPORT_103_TITLE)).toBeTruthy();
  });

  it('FINALIZED 행의 삭제는 disabled다', async () => {
    renderPage();

    const row = (await screen.findByText(REPORT_101_TITLE)).closest('tr') as HTMLElement;
    fireEvent.click(within(row).getByRole('button', { name: /작업 메뉴 열기/ }));

    expect((await screen.findByRole('menuitem', { name: '삭제' })).hasAttribute('disabled')).toBe(true);
  });

  it('제출 실패 후 메뉴 안에 오류를 표시하고 다시 시도할 수 있다', async () => {
    let detailCount = 0;
    server.use(
      http.get('/api/reports/103', () => {
        detailCount += 1;
        return HttpResponse.json({
          success: true,
          data: {
            id: 103,
            inspectionId: 3,
            version: 3,
            status: 'DRAFT',
            groundingCheckPassed: null,
            content: reportContent,
            createdBy: 1,
            createdAt: '2026-06-23T09:15:00',
          },
        });
      }),
      http.post('/api/reports/103/grounding-recheck', () => {
        if (detailCount === 1) {
          return HttpResponse.json(
            { success: false, error: { code: 'REPORT_GROUNDING_MISMATCH', message: '근거 재검증 실패' } },
            { status: 400 },
          );
        }
        return HttpResponse.json({
          success: true,
          data: {
            id: 103,
            inspectionId: 3,
            version: 3,
            status: 'DRAFT',
            groundingCheckPassed: true,
            content: reportContent,
            createdBy: 1,
            createdAt: '2026-06-23T09:15:00',
          },
        });
      }),
      http.post('/api/reports/103/pdf', () =>
        HttpResponse.json({ success: true, data: { pdfUrl: '/api/reports/103/pdf/generated.pdf' } }),
      ),
      http.post('/api/reports/103/finalize', () =>
        HttpResponse.json({
          success: true,
          data: {
            id: 103,
            inspectionId: 3,
            version: 3,
            status: 'FINALIZED',
            groundingCheckPassed: true,
            pdfUrl: '/api/reports/103/pdf/generated.pdf',
            content: reportContent,
            createdBy: 1,
            createdAt: '2026-06-23T09:15:00',
          },
        }),
      ),
    );

    renderPage();

    const row = (await screen.findByText(REPORT_103_TITLE)).closest('tr') as HTMLElement;
    fireEvent.click(within(row).getByRole('button', { name: /작업 메뉴 열기/ }));
    fireEvent.click(await screen.findByRole('menuitem', { name: '발행' }));

    expect(await screen.findByRole('alert')).toBeTruthy();
    fireEvent.click(screen.getByRole('menuitem', { name: '발행' }));

    await waitFor(() => expect(detailCount).toBe(2));
  });

  it('스크롤하면 고정 위치 작업 메뉴를 닫아 앵커 이탈을 방지한다', async () => {
    renderPage();

    const row = (await screen.findByText(REPORT_101_TITLE)).closest('tr') as HTMLElement;
    fireEvent.click(within(row).getByRole('button', { name: /작업 메뉴 열기/ }));
    expect(await screen.findByRole('menuitem', { name: '변경 이력' })).toBeTruthy();

    fireEvent.scroll(window);

    expect(screen.queryByRole('menuitem', { name: '변경 이력' })).toBeNull();
  });
});
