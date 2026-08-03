// @vitest-environment jsdom
// 하자 상세 모달 — 조치 전/조치/조치 완료 사진 3탭(#969, #1193/HAJA-569로 확장) 단위 테스트. 훅을
// 모킹하지 않고 QueryClientProvider + MSW(defectHandlers, mockDefects/mockDefectActionLogs)로 실제
// 데이터 흐름을 그대로 태운다(이 프로젝트 관례 — feature 훅을 직접 mock하는 기존 사례 없음).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import { defectHandlers } from '../api/defectApi.handlers';
import { mockDefects, mockInspectionDefects } from '../mocks/defect.mock';
import type { Defect, DefectActionLogEntry, InspectionDefect } from '../types';
import { DefectDetailModal } from './DefectDetailModal';

// DefectExplainPanel이 마운트 시 자동으로 호출하는 AI 설명 엔드포인트의 최소 목이다.
const explainHandler = http.post('/api/ai/defect-explain', () =>
  HttpResponse.json({
    success: true,
    data: {
      cause: '원인 예시',
      risk: '위험 예시',
      action: '조치 예시',
    },
  }),
);

const server = setupServer(...defectHandlers, explainHandler);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

function renderModal(defectId: number) {
  const groupDefects = mockInspectionDefects.filter((defect) => defect.id === defectId);
  renderModalGroup(groupDefects, defectId);
}

function renderModalGroup(defects: InspectionDefect[], initialDefectId: number) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <DefectDetailModal defects={defects} initialDefectId={initialDefectId} onClose={() => {}} />
    </QueryClientProvider>,
  );
}

// mockDefects id=1(철근 노출)에 actionResult를 얹어 반환하는 오버라이드 핸들러.
function mockDefectWithActionResult(): Defect {
  return {
    ...mockDefects[0],
    actionResult: {
      actionContent: '에폭시 주입 처리',
      actionDate: '2026-07-20',
      assigneeId: 1,
      assigneeName: '홍길동',
      afterPhotoUrl: '/api/media/999/thumbnail',
    },
  };
}

function detailFromSummary(summary: InspectionDefect): Defect {
  const fields = Object.fromEntries(
    Object.entries(summary).filter(([key]) => !['areaRatio', 'detailUrl', 'mediaId'].includes(key)),
  ) as Omit<InspectionDefect, 'areaRatio' | 'detailUrl' | 'mediaId'>;
  return {
    ...fields,
    facilityId: 1,
    facilityName: '강남 오피스타워 A동',
    facilityType: '건물',
  };
}

// id=1 조치중 이력(IN_PROGRESS phase) — mockDefectActionLogs와 별개로 이 테스트 파일 안에서만
// 쓰는 오버라이드용 fixture(server.use로 개수를 테스트마다 다르게 준다).
function inProgressLog(id: number, actionDate: string, photoUrl: string): DefectActionLogEntry {
  return {
    id,
    photoUrl,
    actionContent: `조치중 등록 ${id}`,
    actionDate,
    actionAssigneeId: 1,
    actionAssigneeName: '홍길동',
    createdAt: `${actionDate}T09:00:00.000Z`,
  };
}

function mockActionLogsHandler(logs: { IN_PROGRESS?: DefectActionLogEntry[]; RESOLVED?: DefectActionLogEntry[] }) {
  return http.get('/api/defects/:id/action-logs', ({ request }) => {
    const phase = new URL(request.url).searchParams.get('phase');
    const data = phase === 'RESOLVED' ? (logs.RESOLVED ?? []) : (logs.IN_PROGRESS ?? []);
    const body: ApiResponse<DefectActionLogEntry[]> = { success: true, data };
    return HttpResponse.json(body);
  });
}

describe('DefectDetailModal — 조치 전/조치/조치 완료 사진 3탭(#1193/HAJA-569)', () => {
  it('actionResult와 조치 이력이 없어도 조치 전 사진 탭 디자인을 유지한다', async () => {
    server.use(mockActionLogsHandler({}));
    renderModal(1); // mockDefects id=1: actionResult 없음

    await screen.findByRole('form', { name: '조치 결과 등록' });

    expect(screen.getByRole('tablist', { name: '조치 전/조치/조치 완료 사진' })).not.toBeNull();
    expect(screen.getByRole('tab', { name: '조치 전 사진' }).getAttribute('aria-selected')).toBe('true');
    expect(screen.queryByRole('tab', { name: '조치 사진' })).toBeNull();
    expect(screen.queryByRole('tab', { name: '조치 완료 사진' })).toBeNull();
  });

  it('actionResult가 있고 조치중 이력이 1건이면 "조치 사진" 탭만 추가로 노출되고 select는 없다', async () => {
    server.use(
      http.get('/api/defects/:id', () => {
        const body: ApiResponse<Defect> = { success: true, data: mockDefectWithActionResult() };
        return HttpResponse.json(body);
      }),
      mockActionLogsHandler({ IN_PROGRESS: [inProgressLog(1, '2026-07-20', '/api/media/999/thumbnail')] }),
    );

    renderModal(1);
    await screen.findByText('철근 노출');

    const tablist = screen.getByRole('tablist', { name: '조치 전/조치/조치 완료 사진' });
    expect(tablist).not.toBeNull();

    const beforeTab = screen.getByRole('tab', { name: '조치 전 사진' });
    // action-logs 조회는 defect 로드 이후 별도 비동기 쿼리라(useDefectActionLogs), 탭이 실제로
    // 렌더될 때까지 기다린다.
    const inProgressTab = await screen.findByRole('tab', { name: '조치 사진' });
    expect(beforeTab.getAttribute('aria-selected')).toBe('true');
    expect(inProgressTab.getAttribute('aria-selected')).toBe('false');
    expect(screen.queryByRole('tab', { name: '조치 완료 사진' })).toBeNull();
    expect(screen.queryByLabelText('회차 선택')).toBeNull();

    // 기본 탭에서는 썸네일보다 상세 이미지 URL을 우선한다.
    const image = screen.getByRole('img', { name: '철근 노출 촬영 이미지' }) as HTMLImageElement;
    expect(image.src).toContain('/api/media/901/detail');
  });

  it('"조치 사진" 탭 클릭 시 활성 탭이 전환되고 이력의 photoUrl 이미지가 노출된다', async () => {
    server.use(
      http.get('/api/defects/:id', () => {
        const body: ApiResponse<Defect> = { success: true, data: mockDefectWithActionResult() };
        return HttpResponse.json(body);
      }),
      mockActionLogsHandler({ IN_PROGRESS: [inProgressLog(1, '2026-07-20', '/api/media/999/thumbnail')] }),
    );

    renderModal(1);
    await screen.findByText('철근 노출');

    const inProgressTab = await screen.findByRole('tab', { name: '조치 사진' });
    fireEvent.click(inProgressTab);

    expect(inProgressTab.getAttribute('aria-selected')).toBe('true');
    expect(screen.getByRole('tab', { name: '조치 전 사진' }).getAttribute('aria-selected')).toBe('false');

    const image = screen.getByRole('img', { name: '철근 노출 촬영 이미지' }) as HTMLImageElement;
    expect(image.src).toContain('/api/media/999/thumbnail');
  });

  it('조치중 이력이 2건 이상이면 등록일 select가 노출되고 기본값은 최신(첫 항목)이며, 다른 항목을 고르면 이미지가 바뀐다', async () => {
    server.use(
      http.get('/api/defects/:id', () => {
        const body: ApiResponse<Defect> = { success: true, data: mockDefectWithActionResult() };
        return HttpResponse.json(body);
      }),
      mockActionLogsHandler({
        IN_PROGRESS: [
          inProgressLog(2, '2026-07-22', '/api/media/998/thumbnail'),
          inProgressLog(1, '2026-07-20', '/api/media/999/thumbnail'),
        ],
      }),
    );

    renderModal(1);
    await screen.findByText('철근 노출');
    fireEvent.click(await screen.findByRole('tab', { name: '조치 사진' }));

    const select = (await screen.findByLabelText('회차 선택')) as HTMLSelectElement;
    expect(Array.from(select.options).map((option) => option.textContent)).toEqual([
      '2회차 · 2026-07-22',
      '1회차 · 2026-07-20',
    ]);
    expect(select.value).toBe('2');

    let image = screen.getByRole('img', { name: '철근 노출 촬영 이미지' }) as HTMLImageElement;
    expect(image.src).toContain('/api/media/998/thumbnail');

    fireEvent.change(select, { target: { value: '1' } });

    image = screen.getByRole('img', { name: '철근 노출 촬영 이미지' }) as HTMLImageElement;
    expect(image.src).toContain('/api/media/999/thumbnail');
  });

  it('조치완료(RESOLVED) 이력이 있으면 "조치 완료 사진" 탭이 노출된다', async () => {
    server.use(
      http.get('/api/defects/:id', () => {
        const body: ApiResponse<Defect> = { success: true, data: mockDefectWithActionResult() };
        return HttpResponse.json(body);
      }),
      mockActionLogsHandler({
        IN_PROGRESS: [inProgressLog(1, '2026-07-20', '/api/media/999/thumbnail')],
        RESOLVED: [inProgressLog(3, '2026-07-25', '/api/media/996/thumbnail')],
      }),
    );

    renderModal(1);
    await screen.findByText('철근 노출');

    const resolvedTab = await screen.findByRole('tab', { name: '조치 완료 사진' });
    fireEvent.click(resolvedTab);

    expect(resolvedTab.getAttribute('aria-selected')).toBe('true');
    const image = screen.getByRole('img', { name: '철근 노출 촬영 이미지' }) as HTMLImageElement;
    expect(image.src).toContain('/api/media/996/thumbnail');
  });

  it('선택 전환 중 이미지와 버튼 포커스를 유지하고 요약 헤더·지표를 즉시 갱신한다', async () => {
    const group: InspectionDefect[] = [
      { ...mockInspectionDefects[0], id: 11, mediaId: 901, confidence: 0.91 },
      {
        ...mockInspectionDefects[0],
        id: 12,
        mediaId: 901,
        type: 'CRACK',
        typeLabel: '균열',
        grade: 'E',
        confidence: 0.73,
        bboxX: 0.55,
        bboxY: 0.45,
        bboxW: 0.12,
        bboxH: 0.18,
      },
      {
        ...mockInspectionDefects[0],
        id: 13,
        mediaId: 901,
        type: 'SPALLING',
        typeLabel: '박리·박락',
        grade: 'B',
        confidence: 0.66,
        bboxX: null,
        bboxY: null,
        bboxW: null,
        bboxH: null,
      },
    ];
    server.use(
      http.get('/api/defects/:id', async ({ params }) => {
        if (Number(params.id) === 12) {
          await new Promise((resolve) => setTimeout(resolve, 40));
        }
        const found = group.find((item) => item.id === Number(params.id));
        const detail = found ? detailFromSummary(found) : undefined;
        return HttpResponse.json({ success: true, data: detail } satisfies ApiResponse<Defect | undefined>);
      }),
      mockActionLogsHandler({ IN_PROGRESS: [inProgressLog(1, '2026-07-20', '/api/media/999/thumbnail')] }),
    );

    renderModalGroup(group, 11);
    expect(await screen.findByText('DEF-0011')).not.toBeNull();
    await screen.findByText('원인 예시');

    const image = screen.getByRole('img', { name: '철근 노출 촬영 이미지' }) as HTMLImageElement;
    Object.defineProperties(image, {
      naturalWidth: { configurable: true, value: 800 },
      naturalHeight: { configurable: true, value: 1000 },
    });
    fireEvent.load(image);

    const secondBox = screen.getByRole('button', { name: 'DEF-0012 균열 하자 영역 선택' });
    secondBox.focus();
    fireEvent.click(secondBox);
    expect(screen.getByRole('heading', { name: 'DEF-0012' })).not.toBeNull();
    expect(screen.getByText('73%')).not.toBeNull();
    expect(screen.getByRole('img', { name: '균열 촬영 이미지' })).toBe(image);
    expect(document.activeElement).toBe(secondBox);
    expect(screen.getByText('상세 정보를 불러오는 중...')).not.toBeNull();
    await waitFor(() => expect(screen.queryByText('상세 정보를 불러오는 중...')).toBeNull());

    fireEvent.click(await screen.findByRole('tab', { name: '조치 사진' }));
    expect(screen.getByRole('img', { name: '균열 촬영 이미지' }).getAttribute('src')).toContain('/api/media/999/thumbnail');

    const thirdChip = screen.getByRole('button', { name: 'DEF-0013 · 박리·박락' });
    thirdChip.focus();
    fireEvent.click(thirdChip);
    expect(screen.getByRole('heading', { name: 'DEF-0013' })).not.toBeNull();
    expect(screen.getByText('66%')).not.toBeNull();
    expect(screen.getByRole('tab', { name: '조치 전 사진' }).getAttribute('aria-selected')).toBe('true');
    expect(document.activeElement).toBe(thirdChip);
  });

  it('선택 하자 상세 API가 실패해도 이미지와 전체 선택 목록을 유지한다', async () => {
    const group = [
      { ...mockInspectionDefects[0], id: 21 },
      { ...mockInspectionDefects[0], id: 22, type: 'CRACK' as const, typeLabel: '균열', confidence: 0.77 },
    ];
    server.use(
      http.get('/api/defects/:id', ({ params }) => {
        if (Number(params.id) === 21) {
          return HttpResponse.json(
            { success: false, data: null, error: { code: 'SERVER_ERROR', message: '실패' } },
            { status: 500 },
          );
        }
        const found = group.find((item) => item.id === Number(params.id));
        return HttpResponse.json({ success: true, data: found ? detailFromSummary(found) : null });
      }),
      mockActionLogsHandler({}),
    );

    renderModalGroup(group, 21);
    const image = screen.getByRole('img', { name: '철근 노출 촬영 이미지' });
    expect(await screen.findByRole('alert')).not.toBeNull();
    expect(screen.getByLabelText('이미지 내 하자 선택')).not.toBeNull();

    const nextChip = screen.getByRole('button', { name: 'DEF-0022 · 균열' });
    nextChip.focus();
    fireEvent.click(nextChip);
    expect(screen.getByText('DEF-0022')).not.toBeNull();
    expect(screen.getByRole('img', { name: '균열 촬영 이미지' })).toBe(image);
    expect(document.activeElement).toBe(nextChip);
  });
});
