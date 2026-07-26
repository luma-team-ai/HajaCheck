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
// API 응답 type은 영문 코드(#881) — useInspectionResultReal이 화면 표시용 한글로 번역한다.
const allReviewedDefects: DefectDetailItem[] = [
  defect(1, 'CRACK', 'C', true),
  defect(2, 'CRACK', 'E', true),
  defect(3, 'REBAR_EXPOSURE', 'D', true),
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

function renderPage(initialPath = '/inspections/1/reports') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>
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

    // 화면 표시 라벨('철근노출', 띄어쓰기 없음)은 Figma 표기('철근 노출')와 다르다 — 훅이
    // 번역하는 값(DEFECT_TYPE_CODE_LABELS) 기준이며 testid도 그 값을 따른다.
    const rebarCard = within(screen.getByTestId('defect-type-card-철근노출'));
    expect(rebarCard.getByText('1')).not.toBeNull();
    expect(rebarCard.getByTitle('D등급 · 주의')).not.toBeNull();

    // 하자가 없는 유형은 0건으로 표시된다(칸은 유지)
    expect(within(screen.getByTestId('defect-type-card-도장 손상')).getByText('0')).not.toBeNull();
  });

  it('등급별 분포·유형별 카드는 확정(검수완료) 하자만 집계한다 (#886 P3)', async () => {
    server.use(
      http.get('/api/inspections/:id/defects', () =>
        HttpResponse.json({
          success: true,
          data: [
            defect(1, '균열', 'C', true), // 확정
            defect(2, '균열', 'E', false), // 미검수(DETECTED) — 제외돼야 함
            defect(3, '철근노출', 'D', true), // 확정
          ],
        }),
      ),
    );
    renderPage();
    await screen.findByText(/점검 회차 요약/);

    // 균열은 확정 1건(C)만 반영 — 미검수(E)까지 섞이면 최고등급이 E로 잘못 올라간다
    const crackCard = within(screen.getByTestId('defect-type-card-균열'));
    expect(crackCard.getByText('1')).not.toBeNull();
    expect(crackCard.getByTitle('C등급 · 보통')).not.toBeNull();
    expect(crackCard.queryByTitle('E등급 · 심각')).toBeNull();

    // 전체 최고 등급(요약 스트립)도 확정 하자 기준 — D가 최고(미검수 E는 무시)
    // D는 요약 스트립·철근노출카드 두 곳에 뜨므로 존재 여부만 확인
    expect(screen.getAllByTitle('D등급 · 주의').length).toBeGreaterThan(0);
    expect(screen.queryByTitle('E등급 · 심각')).toBeNull();
  });

  it('"AI 초안이며 법정 제출용 아님" 고지를 노출한다 (#463 요구항목)', async () => {
    renderPage();
    await screen.findByText(/점검 회차 요약/);

    expect(screen.getByText(/법정 제출용/)).not.toBeNull();
  });

  it('설정 토글은 전부 비활성이고 미반영 안내를 노출한다 (#886 P2)', async () => {
    renderPage();
    await screen.findByText(/점검 회차 요약/);

    // grounding 근거(요약·상세)뿐 아니라 조치 권고·종합 의견까지 전부 잠긴다 —
    // 생성 요청에 실리지 않는 값을 켜고 끄게 두면 반영된다고 오인한다.
    for (const name of [/점검 개요/, /하자 현황 요약/, /유형별 상세/, /조치 권고/, /종합 의견/]) {
      expect(screen.getByRole('button', { name }).hasAttribute('disabled')).toBe(true);
    }
    // hover 없이도 보이는 안내가 있어야 한다
    expect(screen.getByText(/아직 생성 결과에 반영되지 않습니다/)).not.toBeNull();
  });

  it('생성 요청 바디에 설정 옵션을 싣지 않는다 (백엔드 연동 시 이 테스트를 갱신할 것)', async () => {
    let body: unknown = 'NOT_CALLED';
    server.use(
      http.post('/api/inspections/:id/reports', async ({ request }) => {
        body = await request.json().catch(() => null);
        return HttpResponse.json({
          success: true,
          data: { id: 77, inspectionId: 1, version: 1, content: {}, status: 'DRAFT', createdBy: 1, createdAt: '2026-07-22T10:00:00Z' },
        });
      }),
    );
    renderPage();
    await screen.findByText(/점검 회차 요약/);

    fireEvent.click(screen.getByRole('button', { name: '보고서 생성 시작' }));

    await waitFor(() => expect(screen.getByText('편집화면')).not.toBeNull());
    // sections/includePhoto 같은 옵션 키가 전혀 실리지 않는다
    expect(JSON.stringify(body ?? {})).not.toContain('section');
    expect(JSON.stringify(body ?? {})).not.toContain('Photo');
  });

  it('inspectionId가 바뀌면 이전 최근작업 요청을 취소해 늦은 응답이 화면을 덮어쓰지 않는다 (#886 P2)', async () => {
    // id=1은 느리게 응답(구버전), id=2는 즉시 응답(신버전)
    server.use(
      http.get('/api/inspections/:id/reports', async ({ params }) => {
        const isOld = String(params.id) === '1';
        if (isOld) await new Promise((r) => setTimeout(r, 120));
        return HttpResponse.json({
          success: true,
          data: [
            {
              id: isOld ? 11 : 22,
              inspectionId: Number(params.id),
              version: isOld ? 1 : 2,
              status: 'DRAFT',
              groundingCheckPassed: null,
              // 목록 행은 회차번호(고정값 8)를 쓰므로, 응답 구분은 날짜로 한다
              createdAt: isOld ? '2026-01-01T00:00:00Z' : '2026-02-02T00:00:00Z',
            },
          ],
        });
      }),
    );

    const { unmount } = renderPage('/inspections/1/reports');
    await screen.findByText(/점검 회차 요약/);
    // 느린 id=1 응답이 도착하기 전에 화면 전환
    unmount();

    renderPage('/inspections/2/reports');
    await screen.findByText(/점검 회차 요약/);
    expect(await screen.findByText(/2026\. 2\. 2\./)).not.toBeNull();

    // 취소된 id=1의 느린 응답이 뒤늦게 도착해도 목록을 덮어쓰지 않아야 한다
    await new Promise((r) => setTimeout(r, 250));
    expect(screen.queryByText(/2026\. 1\. 1\./)).toBeNull();
  });

  it('검수가 미완료면 "보고서 생성 시작"이 비활성화된다', async () => {
    server.use(
      http.get('/api/inspections/:id/defects', () =>
        HttpResponse.json({
          success: true,
          data: [defect(1, 'CRACK', 'C', true), defect(2, 'CRACK', 'E', false)],
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
