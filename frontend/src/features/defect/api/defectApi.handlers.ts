import { http, HttpResponse } from 'msw';
import type { ApiResponse, PageResponse } from '../../../shared/api/types';
import { mockDefectRevisions, mockDefects } from '../mocks/defect.mock';
import type { Defect, DefectRevision, DefectStatus } from '../types';

const DEFAULT_SIZE = 20;

// 백엔드 Defect#changeStatus 와 동일한 순서 — 신규→검수확정→조치대기→조치중→조치완료(역행/스킵 금지).
const NEXT_STATUS: Record<DefectStatus, DefectStatus | null> = {
  DETECTED: 'CONFIRMED',
  CONFIRMED: 'ACTION_PENDING',
  ACTION_PENDING: 'IN_PROGRESS',
  IN_PROGRESS: 'RESOLVED',
  RESOLVED: null,
};

export const defectHandlers = [
  http.get('/api/defects', ({ request }) => {
    const url = new URL(request.url);
    const type = url.searchParams.get('type');
    const grade = url.searchParams.get('grade');
    const status = url.searchParams.get('status');
    const page = Number(url.searchParams.get('page') ?? '0');
    const size = Number(url.searchParams.get('size') ?? String(DEFAULT_SIZE));

    const filtered = mockDefects.filter(
      (defect) =>
        (!type || defect.type === type) &&
        (!grade || defect.grade === grade) &&
        (!status || defect.status === status),
    );

    const content = filtered.slice(page * size, page * size + size);
    const body: ApiResponse<PageResponse<Defect>> = {
      success: true,
      data: { content, page, totalElements: filtered.length },
    };
    return HttpResponse.json(body);
  }),

  http.get('/api/defects/:id', ({ params }) => {
    const id = Number(params.id);
    const found = mockDefects.find((defect) => defect.id === id);

    if (!found) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'DEFECT_NOT_FOUND', message: '하자를 찾을 수 없습니다.' },
      };
      return HttpResponse.json(failure, { status: 404 });
    }

    const body: ApiResponse<Defect> = { success: true, data: found };
    return HttpResponse.json(body);
  }),

  http.patch('/api/defects/:id/status', async ({ params, request }) => {
    const id = Number(params.id);
    const found = mockDefects.find((defect) => defect.id === id);

    if (!found) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'DEFECT_NOT_FOUND', message: '하자를 찾을 수 없습니다.' },
      };
      return HttpResponse.json(failure, { status: 404 });
    }

    const { status } = (await request.json()) as { status: DefectStatus };
    const expectedNext = NEXT_STATUS[found.status];

    if (status !== expectedNext) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'INVALID_STATE_TRANSITION', message: '현재 상태에서는 처리할 수 없는 요청입니다.' },
      };
      return HttpResponse.json(failure, { status: 409 });
    }

    found.status = status;
    const body: ApiResponse<Defect> = { success: true, data: found };
    return HttpResponse.json(body);
  }),

  http.get('/api/defects/:id/revisions', ({ params, request }) => {
    const id = Number(params.id);
    const found = mockDefects.find((defect) => defect.id === id);

    if (!found) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'DEFECT_NOT_FOUND', message: '하자를 찾을 수 없습니다.' },
      };
      return HttpResponse.json(failure, { status: 404 });
    }

    const url = new URL(request.url);
    const page = Number(url.searchParams.get('page') ?? '0');
    const size = Number(url.searchParams.get('size') ?? String(DEFAULT_SIZE));
    const revisions = mockDefectRevisions[id] ?? [];
    const content = revisions.slice(page * size, page * size + size);
    const body: ApiResponse<PageResponse<DefectRevision>> = {
      success: true,
      data: { content, page, totalElements: revisions.length },
    };
    return HttpResponse.json(body);
  }),
];
