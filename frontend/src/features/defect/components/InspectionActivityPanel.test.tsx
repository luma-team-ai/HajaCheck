// @vitest-environment jsdom
// InspectionActivityPanel 단위 테스트 — 활동 항목이 많아도 전체를 렌더링하고 목록 영역에서
// 스크롤하는 동작을 검증한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse, PageResponse } from '../../../shared/api/types';
import type { Defect, DefectRevision } from '../types';
import { InspectionActivityPanel } from './InspectionActivityPanel';

function makeRevision(id: number, minute: number): DefectRevision {
  return {
    id,
    revisedBy: 100,
    fieldChanged: 'status',
    oldValue: 'CONFIRMED',
    newValue: 'IN_PROGRESS',
    reason: null,
    createdAt: `2026-07-01T09:${String(minute).padStart(2, '0')}:00.000Z`,
  };
}

// 하자 3건 x 각 3건 활동 = 총 9건(스크롤이 필요한 긴 목록을 확인하기 위함).
const defects: Defect[] = [1, 2, 3].map((id) => ({
  id,
  inspectionId: 101,
  facilityId: 1,
  facilityName: '강남 오피스타워 A동',
  facilityType: '건물',
  type: 'CRACK',
  typeLabel: '균열',
  grade: 'C',
  status: 'IN_PROGRESS',
  confidence: 0.8,
  reviewed: true,
  bboxX: null,
  bboxY: null,
  bboxW: null,
  bboxH: null,
  crackWidthMm: null,
  crackLengthMm: null,
  imageUrl: null,
  createdAt: '2026-07-01T09:00:00.000Z',
}));

const revisionsByDefect: Record<number, DefectRevision[]> = {
  1: [makeRevision(1, 1), makeRevision(2, 2), makeRevision(3, 3)],
  2: [makeRevision(4, 4), makeRevision(5, 5), makeRevision(6, 6)],
  3: [makeRevision(7, 7), makeRevision(8, 8), makeRevision(9, 9)],
};

const server = setupServer(
  http.get('/api/defects/:id/revisions', ({ params }) => {
    const id = Number(params.id);
    const content = revisionsByDefect[id] ?? [];
    const body: ApiResponse<PageResponse<DefectRevision>> = {
      success: true,
      data: { content, page: 0, totalElements: content.length },
    };
    return HttpResponse.json(body);
  }),
);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

function renderPanel() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <InspectionActivityPanel defects={defects} />
    </QueryClientProvider>,
  );
}

describe('InspectionActivityPanel — 스크롤 목록', () => {
  it('활동 항목을 모두 렌더링하고 더보기 버튼 대신 스크롤 목록을 표시한다', async () => {
    renderPanel();

    const panel = screen.getByLabelText('점검 활동 기록');
    const list = await within(panel).findByRole('list');
    expect(within(list).getAllByRole('listitem')).toHaveLength(9);
    expect(list.className).toContain('inspection-activity-panel__list');
    expect(within(panel).queryByRole('button', { name: /더보기/ })).toBeNull();
  });

  it('상태 변경 항목에 하자 상세 모달과 같은 색상 상태 배지를 표시한다', async () => {
    renderPanel();

    const panel = screen.getByLabelText('점검 활동 기록');
    const badge = (await within(panel).findAllByText('조치중'))[0];

    expect(badge.className).toContain('defect-activity-status-badge');
    expect(badge.className).toContain('bg-orange-50');
    expect(badge.className).toContain('text-orange-500');
    const time = badge.closest('li')?.querySelector('time');
    const defectCode = badge.closest('li')?.querySelector('.inspection-activity-panel__code');
    expect(time).not.toBeNull();
    expect(defectCode?.nextElementSibling).toBe(time);
    expect(time?.parentElement?.nextElementSibling).toBe(badge);
  });

});
