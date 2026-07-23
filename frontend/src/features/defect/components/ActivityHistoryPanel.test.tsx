// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse, PageResponse } from '../../../shared/api/types';
import type { DefectRevision } from '../types';
import { ActivityHistoryPanel } from './ActivityHistoryPanel';

const server = setupServer();

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

function renderPanel(defectId: number) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <ActivityHistoryPanel defectId={defectId} />
    </QueryClientProvider>,
  );
}

describe('ActivityHistoryPanel', () => {
  it('상태 전이 이력을 최신순으로 표시한다', async () => {
    const revisions: DefectRevision[] = [
      {
        id: 2,
        revisedBy: 1,
        fieldChanged: 'status',
        oldValue: 'CONFIRMED',
        newValue: 'ACTION_PENDING',
        reason: null,
        createdAt: '2026-07-01T09:10:00.000Z',
      },
    ];
    server.use(
      http.get('/api/defects/:id/revisions', () => {
        const body: ApiResponse<PageResponse<DefectRevision>> = {
          success: true,
          data: { content: revisions, page: 0, totalElements: 1 },
        };
        return HttpResponse.json(body);
      }),
    );

    renderPanel(1);

    expect(await screen.findByText("상태를 '확인됨'에서 '조치대기'(으)로 변경했습니다.")).not.toBeNull();
  });

  it('이력이 없으면 빈 상태 메시지를 표시한다', async () => {
    server.use(
      http.get('/api/defects/:id/revisions', () => {
        const body: ApiResponse<PageResponse<DefectRevision>> = {
          success: true,
          data: { content: [], page: 0, totalElements: 0 },
        };
        return HttpResponse.json(body);
      }),
    );

    renderPanel(1);

    expect(await screen.findByText('아직 활동 기록이 없습니다.')).not.toBeNull();
  });

  it('사유가 있으면 사유도 함께 표시한다', async () => {
    const revisions: DefectRevision[] = [
      {
        id: 3,
        revisedBy: 1,
        fieldChanged: 'status',
        oldValue: 'DETECTED',
        newValue: 'ACTION_PENDING',
        reason: '경미한 하자라 검수확정 생략',
        createdAt: '2026-07-01T09:00:00.000Z',
      },
    ];
    server.use(
      http.get('/api/defects/:id/revisions', () => {
        const body: ApiResponse<PageResponse<DefectRevision>> = {
          success: true,
          data: { content: revisions, page: 0, totalElements: 1 },
        };
        return HttpResponse.json(body);
      }),
    );

    renderPanel(1);

    expect(await screen.findByText('사유: 경미한 하자라 검수확정 생략')).not.toBeNull();
  });

  it('이력이 페이지 크기(20건)를 넘으면 페이지네이션을 표시하고 다음 페이지를 조회한다(self-review 발견)', async () => {
    // 역행/건너뛰기 전이가 반복되면 이력이 4단계로 고정되지 않아(Defect#changeStatus는 RESOLVED
    // 전까지 상태 왕복을 허용) 20건을 넘는 케이스가 실제로 가능하다 — 그 경우 프론트가 1페이지만
    // 조회하면 오래된 사유 기록이 조용히 잘려나가던 결함을 검증한다.
    const page1: DefectRevision[] = [
      {
        id: 21,
        revisedBy: 1,
        fieldChanged: 'status',
        oldValue: 'CONFIRMED',
        newValue: 'ACTION_PENDING',
        reason: null,
        createdAt: '2026-07-02T09:00:00.000Z',
      },
    ];
    server.use(
      http.get('/api/defects/:id/revisions', ({ request }) => {
        const url = new URL(request.url);
        const page = Number(url.searchParams.get('page') ?? '0');
        const body: ApiResponse<PageResponse<DefectRevision>> = {
          success: true,
          data: { content: page === 0 ? [] : page1, page, totalElements: 21 },
        };
        return HttpResponse.json(body);
      }),
    );

    renderPanel(1);

    const nextButton = await screen.findByRole('button', { name: '다음 페이지' });
    fireEvent.click(nextButton);

    expect(await screen.findByText("상태를 '확인됨'에서 '조치대기'(으)로 변경했습니다.")).not.toBeNull();
  });

  it('이력이 20건 이하면 페이지네이션을 표시하지 않는다', async () => {
    server.use(
      http.get('/api/defects/:id/revisions', () => {
        const body: ApiResponse<PageResponse<DefectRevision>> = {
          success: true,
          data: { content: [], page: 0, totalElements: 0 },
        };
        return HttpResponse.json(body);
      }),
    );

    renderPanel(1);

    await screen.findByText('아직 활동 기록이 없습니다.');
    expect(screen.queryByRole('button', { name: '다음 페이지' })).toBeNull();
  });

  it('조회 실패 시 에러 폴백과 재시도 버튼을 표시한다', async () => {
    server.use(
      http.get('/api/defects/:id/revisions', () => {
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: { code: 'INTERNAL_ERROR', message: '서버 오류가 발생했습니다.' },
        };
        return HttpResponse.json(failure, { status: 500 });
      }),
    );

    renderPanel(1);

    expect(await screen.findByText('활동 기록을 불러오지 못했습니다.')).not.toBeNull();
    expect(screen.getByRole('button', { name: '다시 시도' })).not.toBeNull();
  });
});
