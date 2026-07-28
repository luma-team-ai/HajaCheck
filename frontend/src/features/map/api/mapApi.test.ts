// @vitest-environment jsdom
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import { mapApi } from './mapApi';

const server = setupServer();

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('mapApi', () => {
  it('시설 목록 응답의 실제 등급·건수·썸네일을 지도 위치 모델에 보존한다', async () => {
    server.use(
      http.get('/api/facilities', () =>
        HttpResponse.json({
          success: true,
          data: [
            {
              id: 1,
              name: '한강대교 북단',
              type: '교량-정밀-12개월',
              address: '서울 용산구',
              latitude: 37.5145,
              longitude: 126.9631,
              builtYear: null,
              scale: null,
              inspectionCycleMonths: null,
              nextInspectionDueAt: null,
              highestGrade: 'E',
              warningCount: 4,
              cautionCount: 2,
              thumbnailUrl: '/api/media/9/thumbnail',
            },
          ],
        } satisfies ApiResponse<unknown[]>),
      ),
    );

    const result = await mapApi.getFacilityLocations();

    expect(result).toEqual([
      {
        id: 1,
        name: '한강대교 북단',
        address: '서울 용산구',
        category: '교량-정밀-12개월',
        latitude: 37.5145,
        longitude: 126.9631,
        highestGrade: 'E',
        warningCount: 4,
        cautionCount: 2,
        thumbnailUrl: '/api/media/9/thumbnail',
      },
    ]);
  });
});
