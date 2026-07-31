// @vitest-environment jsdom
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import type { FacilityDefectDetailResponse } from '../types';
import { mockFacilityDefectDetailResponse } from '../mocks/facilityDefect.mock';
import { facilityDefectApi } from './facilityDefectApi';
import { facilityDefectHandlers } from './facilityDefectApi.handlers';

const server = setupServer(...facilityDefectHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('facilityDefectApi.getDetail', () => {
  it('GET /api/defects/{id}(raw) 응답을 화면용 FacilityDefectDetail로 매핑해 반환한다', async () => {
    const res = await facilityDefectApi.getDetail('101');

    expect(res.data.defectType).toBe(mockFacilityDefectDetailResponse.typeLabel);
    expect(res.data.confidencePercent).toBe(mockFacilityDefectDetailResponse.confidence * 100);
    expect(res.data.lengthM).toBe((mockFacilityDefectDetailResponse.crackLengthMm ?? 0) / 1000);
    expect(res.data.foundAt).toBe('2026-06-21');
    // #1369 — bbox 좌표가 매핑에서 버려지지 않고 그대로 전달되는지 고정.
    expect(res.data.bboxX).toBe(mockFacilityDefectDetailResponse.bboxX);
    expect(res.data.bboxY).toBe(mockFacilityDefectDetailResponse.bboxY);
    expect(res.data.bboxW).toBe(mockFacilityDefectDetailResponse.bboxW);
    expect(res.data.bboxH).toBe(mockFacilityDefectDetailResponse.bboxH);
  });
});

describe('facilityDefectApi.updateLocation', () => {
  it('PATCH /api/defects/{id}/location 호출 시 갱신된 location으로 매핑된 응답을 반환한다', async () => {
    const res = await facilityDefectApi.updateLocation('101', '외벽 서측 3층 부근');

    expect(res.data.location).toBe('외벽 서측 3층 부근');
  });

  it('null을 보내면 위치 정보가 제거된 응답을 반환한다', async () => {
    const res = await facilityDefectApi.updateLocation('101', null);

    expect(res.data.location).toBeNull();
  });
});

describe('facilityDefectApi.confirmPreviousDefect', () => {
  it('PATCH /api/defects/{id}/previous-defect 호출 시 매핑된 응답을 반환한다', async () => {
    server.use(
      http.patch('/api/defects/:id/previous-defect', async ({ request }) => {
        const requestBody = (await request.json()) as { previousDefectId: number };
        const body: ApiResponse<FacilityDefectDetailResponse> = {
          success: true,
          data: { ...mockFacilityDefectDetailResponse, id: requestBody.previousDefectId },
        };
        return HttpResponse.json(body);
      }),
    );

    const res = await facilityDefectApi.confirmPreviousDefect('101', 55);

    expect(res.data.id).toBe(55);
  });
});
