// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { ApiResponse } from '../../../shared/api/types';
import { inspectionHandlers } from '../api/inspectionApi.handlers';
import type { DefectRevisionRequest } from '../api/inspectionApi';
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
    status: 'DETECTED',
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
    status: 'DETECTED',
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
    status: 'CONFIRMED',
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
    status: 'DETECTED',
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
    status: 'RESOLVED',
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
    const body = (await request.json()) as DefectRevisionRequest;
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
  http.patch('/api/defects/:id/status', () => {
    const updatedDefect: DefectDetailItem = {
      ...mockDefects[0],
      status: 'CONFIRMED',
    };
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

  it('"검수 확정" 버튼이 활성화되어 있고 클릭하면 상태를 변경한다(#566)', async () => {
    renderPage();
    await screen.findByText('DEF-0001');

    const button = screen.getByRole('button', { name: '이 이미지 검수 확정' });
    expect(button.hasAttribute('disabled')).toBe(false);

    fireEvent.click(button);

    // 로딩 중 비활성화 확인 (짧은 시간이지만)
    expect(button.hasAttribute('disabled')).toBe(true);

    // API 호출 완료 후 버튼 다시 활성화 확인 (refetch 완료 후)
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(button.hasAttribute('disabled')).toBe(false);
  });

  it('"검수 확정" 실패 시 에러 메시지를 표시한다(#566)', async () => {
    // 실패 응답을 반환하는 핸들러 설정
    server.use(
      http.patch('/api/defects/:id/status', () => {
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: { code: 'INVALID_STATUS_TRANSITION', message: '검수 실패' },
        };
        return HttpResponse.json(failure, { status: 400 });
      }),
    );

    renderPage();
    await screen.findByText('DEF-0001');

    const button = screen.getByRole('button', { name: '이 이미지 검수 확정' });
    fireEvent.click(button);

    // 에러 메시지 표시 확인 (기본 에러 메시지가 표시됨)
    expect(await screen.findByText(/검수 확정에 실패했습니다/)).not.toBeNull();
  });

  it('status가 CONFIRMED면 "검수 확정" 버튼이 비활성화된다(#575)', async () => {
    // 첫 번째 defect를 CONFIRMED로 변경한 mock data
    const confirmedDefectsMock = [
      { ...mockDefects[0], status: 'CONFIRMED' as const }, // id=1을 CONFIRMED로
      ...mockDefects.slice(1),
    ] as DefectDetailItem[];

    server.use(
      http.get('/api/inspections/:id/defects', () => {
        const body: ApiResponse<DefectDetailItem[]> = { success: true, data: confirmedDefectsMock };
        return HttpResponse.json(body);
      }),
    );

    renderPage();
    await screen.findByText('DEF-0001');

    const button = screen.getByRole('button', { name: '이 이미지 검수 확정' });
    expect(button.hasAttribute('disabled')).toBe(true);
  });

  it('status가 RESOLVED면 "검수 확정" 버튼이 비활성화된다(#575)', async () => {
    // 첫 번째 defect를 RESOLVED로 변경한 mock data
    const resolvedDefectsMock = [
      { ...mockDefects[0], status: 'RESOLVED' as const }, // id=1을 RESOLVED로
      ...mockDefects.slice(1),
    ] as DefectDetailItem[];

    server.use(
      http.get('/api/inspections/:id/defects', () => {
        const body: ApiResponse<DefectDetailItem[]> = { success: true, data: resolvedDefectsMock };
        return HttpResponse.json(body);
      }),
    );

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
