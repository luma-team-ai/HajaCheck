// @vitest-environment jsdom
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { reportApi } from './reportApi';
import { reportHandlers } from './reportApi.handlers';
import type { ReportDetailResponse } from './reportApi';

const mockReport: ReportDetailResponse = {
  id: 1,
  inspectionId: 1,
  version: 1,
  content: {},
  status: 'DRAFT',
  createdBy: 1,
  createdAt: '2026-07-23T00:00:00Z',
};

const server = setupServer(
  http.post('/api/inspections/1/reports', () =>
    HttpResponse.json({ success: true, data: mockReport }, { status: 201 }),
  ),
  http.get('/api/reports/1', () => HttpResponse.json({ success: true, data: mockReport })),
  http.post('/api/reports/1/clone', () =>
    HttpResponse.json({ success: true, data: { ...mockReport, id: 2, version: 2 } }, { status: 201 }),
  ),
  http.patch('/api/reports/1', async ({ request }) => {
    const body = (await request.json()) as { contentJson: string };
    return HttpResponse.json({
      success: true,
      data: { ...mockReport, content: JSON.parse(body.contentJson), groundingCheckPassed: null },
    });
  }),
  http.post('/api/reports/1/grounding-recheck', () =>
    HttpResponse.json({ success: true, data: { ...mockReport, groundingCheckPassed: true } }),
  ),
  http.post('/api/reports/1/pdf', () =>
    HttpResponse.json({ success: true, data: { pdfUrl: '/api/reports/1/pdf/storage-key' } }),
  ),
  http.post('/api/reports/1/finalize', async ({ request }) => {
    const body = (await request.json()) as { pdfUrl: string };
    return HttpResponse.json({
      success: true,
      data: { ...mockReport, status: 'FINALIZED', pdfUrl: body.pdfUrl },
    });
  }),
  ...reportHandlers,
);

beforeAll(() => server.listen());
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('reportApi', () => {
  it('reportHandlers의 summary 라우트는 상세 :id 라우트보다 우선 매칭된다', async () => {
    const response = await fetch('/api/reports/summary');
    const body = (await response.json()) as {
      success: boolean;
      data: { totalCount: number; finalizedCount: number; draftCount: number };
    };

    expect(body.success).toBe(true);
    expect(body.data.totalCount).toBeGreaterThan(0);
    expect(body.data.finalizedCount + body.data.draftCount).toBe(body.data.totalCount);
  });

  it('generateReportDraft는 초안 보고서를 생성한다', async () => {
    const response = await reportApi.generateReportDraft(1);

    expect(response.data.status).toBe('DRAFT');
    expect(response.data.inspectionId).toBe(1);
  });

  it('generateReportDraft는 signal이 abort되면 요청을 취소한다', async () => {
    const controller = new AbortController();
    controller.abort();

    await expect(reportApi.generateReportDraft(1, undefined, controller.signal)).rejects.toThrow();
  });

  it('getReport는 보고서 상세를 조회한다', async () => {
    const response = await reportApi.getReport(1);
    expect(response.data.id).toBe(1);
  });

  it('cloneReport는 다음 버전 초안을 생성한다', async () => {
    const response = await reportApi.cloneReport(1);
    expect(response.data.id).toBe(2);
    expect(response.data.version).toBe(2);
    expect(response.data.status).toBe('DRAFT');
  });

  it('updateContent는 content를 JSON 문자열로 감싸 PATCH하고, groundingCheckPassed가 리셋된 응답을 받는다', async () => {
    const response = await reportApi.updateContent(1, { overview: { purpose: '수정됨' } });
    expect(response.data.content).toEqual({ overview: { purpose: '수정됨' } });
    expect(response.data.groundingCheckPassed).toBeNull();
  });

  it('groundingRecheck는 groundingCheckPassed를 갱신한 응답을 받는다', async () => {
    const response = await reportApi.groundingRecheck(1);
    expect(response.data.groundingCheckPassed).toBe(true);
  });

  it('uploadPdf는 파일을 업로드하고 pdfUrl을 받는다', async () => {
    const response = await reportApi.uploadPdf(1, new Blob(['pdf']), 'report.pdf');
    expect(response.data.pdfUrl).toBe('/api/reports/1/pdf/storage-key');
  });

  it('finalizeReport는 보고서를 FINALIZED로 확정한다', async () => {
    const response = await reportApi.finalizeReport(1, '/api/reports/1/pdf/storage-key');
    expect(response.data.status).toBe('FINALIZED');
    expect(response.data.pdfUrl).toBe('/api/reports/1/pdf/storage-key');
  });
});
