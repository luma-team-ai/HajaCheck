// @vitest-environment jsdom
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { reportApi } from './reportApi';
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
);

beforeAll(() => server.listen());
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('reportApi', () => {
  it('generateReportDraft는 초안 보고서를 생성한다', async () => {
    const response = await reportApi.generateReportDraft(1);

    expect(response.data.status).toBe('DRAFT');
    expect(response.data.inspectionId).toBe(1);
  });

  it('generateReportDraft는 signal이 abort되면 요청을 취소한다', async () => {
    const controller = new AbortController();
    controller.abort();

    await expect(reportApi.generateReportDraft(1, controller.signal)).rejects.toThrow();
  });
});
