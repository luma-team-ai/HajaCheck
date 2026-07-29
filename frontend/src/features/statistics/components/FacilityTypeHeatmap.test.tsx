// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import type { FacilityTypeMonthlyHeatmapCell } from '../types';
import { FacilityTypeHeatmap } from './FacilityTypeHeatmap';

const server = setupServer();

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

function renderHeatmap() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <FacilityTypeHeatmap />
    </QueryClientProvider>,
  );
}

describe('FacilityTypeHeatmap', () => {
  // 회귀 테스트: 등록/점검 이력이 없는 시설물군(예: '기타')이 실데이터에 아예 없어도 0건 행으로
  // 고정 노출돼야 한다(사용자 리포트 — 이전에는 데이터가 있는 카테고리만 행으로 표시돼 '기타'가
  // 통째로 빠졌다).
  it('실데이터에 없는 시설물 유형도 0건 행으로 항상 노출한다', async () => {
    server.use(
      http.get('/api/statistics/facility-type-heatmap', () =>
        HttpResponse.json({
          success: true,
          data: [
            { facilityTypeCategory: '건물', month: '2026-06', defectCount: 5 },
          ],
        } satisfies ApiResponse<FacilityTypeMonthlyHeatmapCell[]>),
      ),
    );

    renderHeatmap();

    expect(await screen.findByText('건물')).not.toBeNull();
    expect(screen.getByText('교량')).not.toBeNull();
    expect(screen.getByText('도로')).not.toBeNull();
    expect(screen.getByText('기타')).not.toBeNull();
    // 데이터가 없는 카테고리는 0건 셀로 렌더된다(존재하는 월 컬럼 기준)
    expect(screen.getByLabelText('기타 · 6월 · 0건')).not.toBeNull();
  });

  it('데이터가 전혀 없으면(월도 0건) 빈 상태 메시지를 보여준다', async () => {
    server.use(
      http.get('/api/statistics/facility-type-heatmap', () =>
        HttpResponse.json({ success: true, data: [] } satisfies ApiResponse<FacilityTypeMonthlyHeatmapCell[]>),
      ),
    );

    renderHeatmap();

    expect(await screen.findByText('표시할 데이터가 없습니다.')).not.toBeNull();
    expect(screen.queryByText('기타')).toBeNull();
  });
});
