// @vitest-environment jsdom
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import { statisticsApi } from './statisticsApi';

const captured = new Map<string, string>();

const server = setupServer(
  http.get('/api/statistics/summary', ({ request }) => {
    captured.set('summary', request.url);
    return HttpResponse.json({ success: true, data: null } satisfies ApiResponse<null>);
  }),
  http.get('/api/statistics/monthly-trend', ({ request }) => {
    captured.set('monthly-trend', request.url);
    return HttpResponse.json({ success: true, data: [] } satisfies ApiResponse<[]>);
  }),
  http.get('/api/statistics/defect-type-distribution', ({ request }) => {
    captured.set('defect-type-distribution', request.url);
    return HttpResponse.json({ success: true, data: [] } satisfies ApiResponse<[]>);
  }),
  http.get('/api/statistics/grade-distribution', ({ request }) => {
    captured.set('grade-distribution', request.url);
    return HttpResponse.json({ success: true, data: [] } satisfies ApiResponse<[]>);
  }),
  http.get('/api/statistics/facility-type-heatmap', ({ request }) => {
    captured.set('facility-type-heatmap', request.url);
    return HttpResponse.json({ success: true, data: [] } satisfies ApiResponse<[]>);
  }),
  http.get('/api/statistics/facility-summary', ({ request }) => {
    captured.set('facility-summary', request.url);
    return HttpResponse.json({ success: true, data: [] } satisfies ApiResponse<[]>);
  }),
);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
beforeEach(() => captured.clear());
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function assertParam(endpoint: string, name: string, expected: string) {
  const url = new URL(captured.get(endpoint) ?? '');
  expect(url.searchParams.get(name)).toBe(expected);
}

describe('6종 통계 API — period 파라미터 전달', () => {
  it.each(['3m', '6m', '1y'] as const)(
    'getSummary: period=%s가 요청 URL에 전달된다',
    async (period) => {
      await statisticsApi.getSummary({ period, facilityId: 'all' });
      assertParam('summary', 'period', period);
      assertParam('summary', 'facilityId', 'all');
    },
  );

  it.each(['3m', '6m', '1y'] as const)(
    'getMonthlyTrend: period=%s가 요청 URL에 전달된다',
    async (period) => {
      await statisticsApi.getMonthlyTrend({ period });
      assertParam('monthly-trend', 'period', period);
    },
  );

  it.each(['3m', '6m', '1y'] as const)(
    'getFacilityTypeHeatmap: period=%s가 요청 URL에 전달된다',
    async (period) => {
      await statisticsApi.getFacilityTypeHeatmap({ period, facilityId: 'all' });
      assertParam('facility-type-heatmap', 'period', period);
      assertParam('facility-type-heatmap', 'facilityId', 'all');
    },
  );
});

describe('6종 통계 API — facilityId 파라미터 전달', () => {
  it('getDefectTypeDistribution: facilityId=1이 요청 URL에 전달된다', async () => {
    await statisticsApi.getDefectTypeDistribution({ facilityId: '1' });
    assertParam('defect-type-distribution', 'facilityId', '1');
  });

  it('getGradeDistribution: facilityId=2가 요청 URL에 전달된다', async () => {
    await statisticsApi.getGradeDistribution({ facilityId: '2' });
    assertParam('grade-distribution', 'facilityId', '2');
  });

  it('getFacilitySummary: facilityId=all이 요청 URL에 전달된다', async () => {
    await statisticsApi.getFacilitySummary({ facilityId: 'all' });
    assertParam('facility-summary', 'facilityId', 'all');
  });

  it('getSummary: 파라미터 없이 호출해도 기본 URL이 정상이다', async () => {
    await statisticsApi.getSummary();
    const url = new URL(captured.get('summary') ?? '');
    expect(url.searchParams.has('period')).toBe(false);
    expect(url.searchParams.has('facilityId')).toBe(false);
  });
});
