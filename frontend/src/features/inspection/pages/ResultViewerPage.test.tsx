// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { ApiResponse } from '../../../shared/api/types';
import { inspectionHandlers } from '../api/inspectionApi.handlers';
import type { InspectionResponse } from '../api/inspectionApi.types';
import type { DefectDetailItem } from '../api/inspectionApi.types';
import { ResultViewerPage } from './ResultViewerPage';

// 테스트용 목 데이터
const mockInspection: InspectionResponse = {
  id: 1,
  facilityId: 1,
  createdBy: 1,
  assignedInspectorId: 1,
  roundNo: 1,
  inspectionDate: '2026-07-22',
  status: 'ANALYZED',
  createdAt: '2026-07-22T10:00:00Z',
};

const mockDefects: DefectDetailItem[] = [
  {
    id: 1,
    inspectionId: 1,
    type: '균열',
    grade: 'C',
    status: '신규',
    confidence: 0.98,
    isReviewed: false,
    bboxX: 0.12,
    bboxY: 0.3,
    bboxW: 0.18,
    bboxH: 0.08,
    crackWidthMm: 3.2,
    crackLengthMm: 45,
    createdAt: '2026-07-22T10:00:00Z',
  },
  {
    id: 2,
    inspectionId: 1,
    type: '박리박락',
    grade: 'B',
    status: '신규',
    confidence: 0.81,
    isReviewed: false,
    bboxX: 0.55,
    bboxY: 0.42,
    bboxW: 0.12,
    bboxH: 0.15,
    createdAt: '2026-07-22T10:00:00Z',
  },
  {
    id: 3,
    inspectionId: 1,
    type: '철근노출',
    grade: 'D',
    status: '검수확정',
    confidence: 0.67,
    isReviewed: true,
    bboxX: 0.3,
    bboxY: 0.6,
    bboxW: 0.25,
    bboxH: 0.1,
    createdAt: '2026-07-22T10:00:00Z',
  },
  {
    id: 4,
    inspectionId: 1,
    type: '철근노출',
    grade: 'E',
    status: '신규',
    confidence: 0.58,
    isReviewed: false,
    bboxX: 0.7,
    bboxY: 0.15,
    bboxW: 0.1,
    bboxH: 0.1,
    createdAt: '2026-07-22T10:00:00Z',
  },
  {
    id: 5,
    inspectionId: 1,
    type: '박리박락',
    grade: 'A',
    status: '조치완료',
    confidence: 0.45,
    isReviewed: true,
    bboxX: 0.05,
    bboxY: 0.75,
    bboxW: 0.2,
    bboxH: 0.08,
    createdAt: '2026-07-22T10:00:00Z',
  },
];

// 새로운 API 엔드포인트 mock
const testHandlers = [
  ...inspectionHandlers,
  http.get('/api/inspections/:id', () => {
    const body: ApiResponse<InspectionResponse> = { success: true, data: mockInspection };
    return HttpResponse.json(body);
  }),
  http.get('/api/inspections/:id/defects', () => {
    const body: ApiResponse<DefectDetailItem[]> = { success: true, data: mockDefects };
    return HttpResponse.json(body);
  }),
  http.post('/api/ai/defect-explain', () => {
    const body = {
      success: true,
      data: {
        cause: '콘크리트 표면의 환경 노출로 인한 수축 응력',
        risk: '방치 시 균열 진전으로 구조 안정성 악화',
        action: '우레탄 같은 유연한 충전재로 밀봉 권장',
      },
    };
    return HttpResponse.json(body);
  }),
  http.patch('/api/defects/:id', async ({ request }) => {
    const body = (await request.json()) as any;
    // reason은 필수 필드 (1-500자)
    if (!body.reason || body.reason.trim().length === 0 || body.reason.trim().length > 500) {
      return HttpResponse.json(
        { success: false, error: { code: 'INVALID_INPUT', message: 'reason은 필수이고 1-500자여야 합니다.' } },
        { status: 400 },
      );
    }
    const updatedDefect: DefectDetailItem = mockDefects[0];
    return HttpResponse.json({ success: true, data: updatedDefect });
  }),
];

const server = setupServer(...testHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  // vitest globals 미설정 환경이라 RTL 자동 cleanup이 안 걸림 — 명시 호출 필요
  cleanup();
});
afterAll(() => server.close());

function renderPage(path: string = '/inspections/1/viewer'): void {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/inspections/:id/viewer" element={<ResultViewerPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ResultViewerPage (통합 테스트)', () => {
  it('정상 렌더: 점검 결과 데이터(결함코드, 시설물명)를 로드해 표시한다', async () => {
    renderPage();

    // mock 데이터에서 실제 값 확인 (defectCode는 이제 DEF-{id} 형식)
    expect(await screen.findByText('DEF-0001')).not.toBeNull();
    expect(await screen.findByText('강남 오피스타워 A동')).not.toBeNull();
  });

  it('선택된 하자가 필터로 제외되면 목록의 첫 항목으로 자동 대체된다', async () => {
    renderPage();
    await screen.findByText('DEF-0001');

    // id=2(박리박락, confidence 0.81)를 선택
    fireEvent.click(screen.getByTitle(/박리박락/));
    // AI 패널에서 AI response 확인
    expect(await screen.findByText(/콘크리트 표면의 환경 노출로 인한 수축 응력/)).not.toBeNull();

    // 신뢰도 threshold를 0.9로 올려 id=2를 필터에서 제외
    fireEvent.change(screen.getByRole('slider'), { target: { value: '0.9' } });

    // 선택이 남아있는 첫 항목(id=1, 균열)으로 자동 대체된다 — AI 패널도 재렌더
    expect(await screen.findByText(/콘크리트 표면의 환경 노출로 인한 수축 응력/)).not.toBeNull();
  });

  it('필터 결과가 0건이면(원본 데이터는 있음) 안내 메시지를 표시한다(#368)', async () => {
    renderPage();
    await screen.findByText('DEF-0001');

    // 신뢰도 threshold를 최대로 올려 모든 하자(최고 confidence 0.98)를 필터에서 제외
    fireEvent.change(screen.getByRole('slider'), { target: { value: '1' } });

    expect(await screen.findByText('조건에 맞는 하자가 없습니다.')).not.toBeNull();
  });

  it('"검수 확정" 버튼은 백엔드 미구현으로 비활성화되어 있다(#553, inspection 상태 transition API 확인 필요)', async () => {
    renderPage();
    await screen.findByText('DEF-0001');

    const button = screen.getByRole('button', { name: '이 이미지 검수 확정' });
    expect(button.hasAttribute('disabled')).toBe(true);
  });

  it('오탐 삭제 버튼이 활성화되어 있다(#553)', async () => {
    renderPage();
    await screen.findByText('DEF-0001');

    const button = screen.getByRole('button', { name: '오탐 삭제' });
    expect(button.hasAttribute('disabled')).toBe(false);
  });

  it('등급 수정 버튼이 활성화되어 있다(#553)', async () => {
    renderPage();
    await screen.findByText('DEF-0001');

    const button = screen.getByRole('button', { name: '등급 수정' });
    expect(button.hasAttribute('disabled')).toBe(false);
  });

  it('하자 마커 클릭 → AI 패널 요약 텍스트가 선택된 하자로 갱신된다', async () => {
    renderPage();
    await screen.findByText('DEF-0001');

    // 초기 상태: id=1(균열)의 AI 설명 표시
    expect(await screen.findByText(/콘크리트 표면의 환경 노출로 인한 수축 응력/)).not.toBeNull();

    // id=2(박리박락) 마커 클릭
    const secondDefectButton = screen.getByTitle(/박리박락/);
    fireEvent.click(secondDefectButton);

    // AI 패널의 설명이 id=2로 갱신됨 (같은 mock 응답 재사용)
    expect(await screen.findByText(/콘크리트 표면의 환경 노출로 인한 수축 응력/)).not.toBeNull();
  });

  it('빈 데이터: 탐지된 하자가 없으면 해당 메시지를 표시한다', async () => {
    // 빈 defects 배열 응답으로 오버라이드
    server.use(
      http.get('/api/inspections/:id/defects', () => {
        const emptyResult: ApiResponse<DefectDetailItem[]> = { success: true, data: [] };
        return HttpResponse.json(emptyResult);
      }),
    );

    renderPage();
    expect(await screen.findByText('탐지된 하자가 없습니다.')).not.toBeNull();
  });
});
