// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import type { FacilityTypeMonthlyHeatmapCell } from '../types';
import { FacilityTypeHeatmap } from './FacilityTypeHeatmap';

const server = setupServer();

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  vi.setSystemTime(new Date('2026-08-15T00:00:00'));
});
afterEach(() => {
  server.resetHandlers();
  cleanup();
  vi.useRealTimers();
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

  // 회귀 테스트(#1696): 그 기간 전체가 0건(빈 배열 응답)이어도 그리드는 사라지지 않고 행 4종 +
  // 현재 월(오늘=2026-08) 열이 그대로 노출돼야 한다. 이전에는 data.length===0 분기가 그리드
  // 전체를 "표시할 데이터가 없습니다"로 가렸는데, 이는 "0건 카테고리도 항상 행으로 노출한다"는
  // 위 결정과 모순이었다.
  it('데이터가 전혀 없어도(빈 배열 응답) 그리드가 렌더되고 행 4종과 현재 월 열이 노출된다', async () => {
    server.use(
      http.get('/api/statistics/facility-type-heatmap', () =>
        HttpResponse.json({ success: true, data: [] } satisfies ApiResponse<FacilityTypeMonthlyHeatmapCell[]>),
      ),
    );

    renderHeatmap();

    expect(await screen.findByText('건물')).not.toBeNull();
    expect(screen.getByText('교량')).not.toBeNull();
    expect(screen.getByText('도로')).not.toBeNull();
    expect(screen.getByText('기타')).not.toBeNull();
    // 현재 월(2026-08) 열이 노출되고, 모든 셀이 0건으로 렌더된다
    expect(screen.getByText('8월')).not.toBeNull();
    expect(screen.getByLabelText('건물 · 8월 · 0건')).not.toBeNull();
    expect(screen.queryByText('표시할 데이터가 없습니다.')).toBeNull();
  });
});
