// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { within } from '@testing-library/react';
import type { ApiResponse } from '../../../shared/api/types';
import type {
  InspectionResponse,
  DefectDetailItem,
  MediaResponse,
} from '../../inspection/api/inspectionApi.types';
import type { ReportSummaryResponse } from '../api/reportApi';
import { ReportEntryPage } from './ReportEntryPage';

const mockInspection: InspectionResponse = {
  id: 1,
  facilityId: 1,
  createdBy: 1,
  assignedInspectorId: 1,
  roundNo: 8,
  inspectionDate: '2026-07-22',
  status: 'ANALYZED',
  createdAt: '2026-07-22T10:00:00Z',
};

function defect(
  id: number,
  type: DefectDetailItem['type'],
  grade: DefectDetailItem['grade'],
  isReviewed: boolean,
): DefectDetailItem {
  return {
    id,
    inspectionId: 1,
    type,
    grade,
    status: isReviewed ? 'CONFIRMED' : 'DETECTED',
    confidence: 0.9,
    isReviewed,
    bboxX: 0.1,
    bboxY: 0.1,
    bboxW: 0.1,
    bboxH: 0.1,
    createdAt: '2026-07-22T10:00:00Z',
  };
}

// 균열 2건(C·E) · 철근노출 1건(D) — 전부 검수 완료
const allReviewedDefects: DefectDetailItem[] = [
  defect(1, '균열', 'C', true),
  defect(2, '균열', 'E', true),
  defect(3, '철근노출', 'D', true),
];

const mockMedia: MediaResponse[] = [
  {
    id: 67,
    inspectionId: 1,
    fileType: 'IMAGE',
    thumbnailUrl: '/api/media/67/thumbnail',
    detailUrl: '/api/media/67/detail',
    mimeType: 'image/jpeg',
    capturedAt: '2026-07-22T10:00:00Z',
    gpsLat: null,
    gpsLng: null,
    createdAt: '2026-07-22T10:00:00Z',
  },
  {
    id: 68,
    inspectionId: 1,
    fileType: 'IMAGE',
    thumbnailUrl: '/api/media/68/thumbnail',
    detailUrl: '/api/media/68/detail',
    mimeType: 'image/jpeg',
    capturedAt: '2026-07-22T10:05:00Z',
    gpsLat: null,
    gpsLng: null,
    createdAt: '2026-07-22T10:05:00Z',
  },
];

const mockFacility = {
  id: 1,
  name: '강남 오피스타워 A동',
  type: '건물',
  address: '서울시 강남구',
  builtYear: 2020,
  scale: 'SMALL',
  nextInspectionDueAt: '2026-08-22',
};

const server = setupServer(
  http.get('/api/inspections/:id', () =>
    HttpResponse.json({ success: true, data: mockInspection } satisfies ApiResponse<InspectionResponse>),
  ),
  http.get('/api/inspections/:id/defects', () =>
    HttpResponse.json({ success: true, data: allReviewedDefects } satisfies ApiResponse<DefectDetailItem[]>),
  ),
  http.get('/api/inspections/:id/media', () =>
    HttpResponse.json({ success: true, data: mockMedia } satisfies ApiResponse<MediaResponse[]>),
  ),
  http.get('/api/facilities/:id', () => HttpResponse.json({ success: true, data: mockFacility })),
  http.get('/api/inspections/:id/reports', () =>
    HttpResponse.json({ success: true, data: [] } satisfies ApiResponse<ReportSummaryResponse[]>),
  ),
);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/inspections/1/reports']}>
        <Routes>
          <Route path="/inspections/:id/reports" element={<ReportEntryPage />} />
          <Route path="/inspections/:id/reports/generate" element={<div>편집화면</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ReportEntryPage (보고서 생성 진입점, #876)', () => {
  it('회차 번호와 요약 지표(이미지 수·확정 하자·최고 등급)를 렌더한다', async () => {
    renderPage();

    expect(await screen.findByText(/점검 회차 요약 — 8회차/)).not.toBeNull();
    // 미디어 2건
    expect(screen.getByText('2장')).not.toBeNull();
    // 확정 하자 3건(전부 isReviewed)
    expect(screen.getByText('3')).not.toBeNull();
    // 최고 등급 = 가장 심각한 E (A가 경미, E가 심각)
    expect(screen.getAllByTitle('E등급 · 심각').length).toBeGreaterThan(0);
  });

  it('유형별 카드를 한글 DefectType으로 집계한다 (영문 enum 매칭 회귀 방지)', async () => {
    renderPage();
    await screen.findByText(/점검 회차 요약/);

    // 균열 2건(C·E) — 유형 내 최고 등급은 E
    const crackCard = within(screen.getByTestId('defect-type-card-균열'));
    expect(crackCard.getByText('2')).not.toBeNull();
    expect(crackCard.getByTitle('E등급 · 심각')).not.toBeNull();

    // 데이터의 타입값은 '철근노출'(띄어쓰기 없음)인데 화면 라벨은 Figma 표기('철근 노출')다.
    // 영문 enum('REBAR_EXPOSURE')으로 매칭하면 여기서 0건이 되어 조용히 깨진다.
    const rebarCard = within(screen.getByTestId('defect-type-card-철근노출'));
    expect(rebarCard.getByText('1')).not.toBeNull();
    expect(rebarCard.getByTitle('D등급 · 주의')).not.toBeNull();

    // 하자가 없는 유형은 0건으로 표시된다(칸은 유지)
    expect(within(screen.getByTestId('defect-type-card-도장 손상')).getByText('0')).not.toBeNull();
  });

  it('"AI 초안이며 법정 제출용 아님" 고지를 노출한다 (#463 요구항목)', async () => {
    renderPage();
    await screen.findByText(/점검 회차 요약/);

    expect(screen.getByText(/법정 제출용/)).not.toBeNull();
  });

  it('grounding 근거 섹션(하자 현황 요약·유형별 상세)은 해제할 수 없다', async () => {
    renderPage();
    await screen.findByText(/점검 회차 요약/);

    const summaryToggle = screen.getByRole('button', { name: /하자 현황 요약/ });
    const detailToggle = screen.getByRole('button', { name: /유형별 상세/ });
    expect(summaryToggle.hasAttribute('disabled')).toBe(true);
    expect(detailToggle.hasAttribute('disabled')).toBe(true);
  });

  it('검수가 미완료면 "보고서 생성 시작"이 비활성화된다', async () => {
    server.use(
      http.get('/api/inspections/:id/defects', () =>
        HttpResponse.json({
          success: true,
          data: [defect(1, '균열', 'C', true), defect(2, '균열', 'E', false)],
        }),
      ),
    );
    renderPage();
    await screen.findByText(/점검 회차 요약/);

    expect(screen.getByRole('button', { name: '보고서 생성 시작' }).hasAttribute('disabled')).toBe(true);
  });

  it('생성에 성공하면 편집화면으로 reportId 쿼리를 달아 이동한다', async () => {
    let posted = false;
    server.use(
      http.post('/api/inspections/:id/reports', () => {
        posted = true;
        return HttpResponse.json({
          success: true,
          data: { id: 77, inspectionId: 1, version: 1, content: {}, status: 'DRAFT', createdBy: 1, createdAt: '2026-07-22T10:00:00Z' },
        });
      }),
    );
    renderPage();
    await screen.findByText(/점검 회차 요약/);

    fireEvent.click(screen.getByRole('button', { name: '보고서 생성 시작' }));

    await waitFor(() => {
      expect(posted).toBe(true);
      expect(screen.getByText('편집화면')).not.toBeNull();
    });
  });

  it('최근 작업 내역이 있으면 목록과 "이어서 편집" 버튼을 노출한다', async () => {
    server.use(
      http.get('/api/inspections/:id/reports', () =>
        HttpResponse.json({
          success: true,
          data: [
            {
              id: 42,
              inspectionId: 1,
              version: 1,
              status: 'DRAFT',
              groundingCheckPassed: null,
              createdAt: '2026-06-22T10:00:00Z',
            },
          ] satisfies ReportSummaryResponse[],
        }),
      ),
    );
    renderPage();
    await screen.findByText(/점검 회차 요약/);

    const editButton = await screen.findByRole('button', { name: '이어서 편집' });
    fireEvent.click(editButton);
    expect(await screen.findByText('편집화면')).not.toBeNull();
  });
});
