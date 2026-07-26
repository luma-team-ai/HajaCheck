// @vitest-environment jsdom
// InspectionActivityPanel 단위 테스트 — 활동 항목이 많을 때 처음 5건만 보여주고 "더보기" 클릭 시
// 나머지를 클라이언트 사이드로 펼치는 동작(Figma 정렬, #937)을 검증한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
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

// 하자 3건 x 각 3건 활동 = 총 9건(초기 5건 노출 기준을 넘겨 "더보기" 동작을 확인하기 위함).
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

describe('InspectionActivityPanel — 더보기', () => {
  it('활동 항목이 5건을 넘으면 처음 5건만 보여주고 더보기 버튼을 표시한다', async () => {
    renderPanel();

    const panel = screen.getByLabelText('점검 활동 기록');
    const list = await within(panel).findByRole('list');
    expect(within(list).getAllByRole('listitem')).toHaveLength(5);
    expect(within(panel).getByRole('button', { name: '더보기 (4)' })).not.toBeNull();
  });

  it('더보기를 클릭하면 남은 항목이 모두 펼쳐지고 버튼이 사라진다', async () => {
    renderPanel();

    const panel = screen.getByLabelText('점검 활동 기록');
    await within(panel).findByRole('list');

    fireEvent.click(within(panel).getByRole('button', { name: '더보기 (4)' }));

    const list = within(panel).getByRole('list');
    expect(within(list).getAllByRole('listitem')).toHaveLength(9);
    expect(within(panel).queryByRole('button', { name: /더보기/ })).toBeNull();
  });
});
