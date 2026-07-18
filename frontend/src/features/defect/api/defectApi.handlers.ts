import { http, HttpResponse } from 'msw';
import type { ApiResponse, PageResponse } from '../../../shared/api/types';
import { mockDefects } from '../mocks/defect.mock';
import type { Defect } from '../types';

const DEFAULT_SIZE = 20;

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
];
