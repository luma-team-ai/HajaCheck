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
  it('GET /api/facilities/map 응답의 실제 등급·건수·주소·썸네일을 지도 위치 모델에 보존한다(#1656/#1657)', async () => {
    server.use(
      http.get('/api/facilities/map', () =>
        HttpResponse.json({
          success: true,
          data: {
            facilities: [
              {
                id: 1,
                name: '한강대교 북단',
                facilityType: '교량-정밀-12개월',
                address: '서울 용산구 이촌동 302-14',
                latitude: 37.5145,
                longitude: 126.9631,
                highestGrade: 'E',
                warningCount: 4,
                cautionCount: 2,
                thumbnailUrl: '/api/media/9/thumbnail',
              },
            ],
          },
        } satisfies ApiResponse<unknown>),
      ),
    );

    const result = await mapApi.getFacilityLocations();

    expect(result).toEqual([
      {
        id: 1,
        name: '한강대교 북단',
        address: '서울 용산구 이촌동 302-14',
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

  it('address를 하드코딩된 null로 덮어쓰지 않고 응답의 실제 값을 그대로 보존한다(주소 검색 회귀 해소)', async () => {
    server.use(
      http.get('/api/facilities/map', () =>
        HttpResponse.json({
          success: true,
          data: {
            facilities: [
              {
                id: 1,
                name: '한강대교 북단',
                facilityType: '교량',
                address: '서울 용산구 이촌동 302-14',
                latitude: 37.5145,
                longitude: 126.9631,
                highestGrade: null,
                warningCount: null,
                cautionCount: null,
                thumbnailUrl: null,
              },
              {
                id: 2,
                name: '주소 미입력 시설물',
                facilityType: '건물',
                address: null,
                latitude: 37.5,
                longitude: 127.0,
                highestGrade: null,
                warningCount: null,
                cautionCount: null,
                thumbnailUrl: null,
              },
            ],
          },
        } satisfies ApiResponse<unknown>),
      ),
    );

    const result = await mapApi.getFacilityLocations();

    expect(result[0].address).toBe('서울 용산구 이촌동 302-14');
    expect(result[1].address).toBeNull();
  });

  it('좌표(latitude/longitude)가 NULL인 시설물도 걸러내지 않고 그대로 포함한다(#1657 — 목록엔 남기고 마커에서만 제외)', async () => {
    server.use(
      http.get('/api/facilities/map', () =>
        HttpResponse.json({
          success: true,
          data: {
            facilities: [
              {
                id: 1,
                name: '좌표 없는 시설물',
                facilityType: '건물',
                address: '경기 화성시 향남읍',
                latitude: null,
                longitude: null,
                highestGrade: null,
                warningCount: null,
                cautionCount: null,
                thumbnailUrl: null,
              },
            ],
          },
        } satisfies ApiResponse<unknown>),
      ),
    );

    const result = await mapApi.getFacilityLocations();

    expect(result).toHaveLength(1);
    expect(result[0]).toMatchObject({ id: 1, latitude: null, longitude: null, address: '경기 화성시 향남읍' });
  });

  it('facilityType이 없으면 카테고리를 "기타"로 폴백한다', async () => {
    server.use(
      http.get('/api/facilities/map', () =>
        HttpResponse.json({
          success: true,
          data: {
            facilities: [
              {
                id: 2,
                name: '유형 미상 시설물',
                facilityType: null,
                address: null,
                latitude: 37.5,
                longitude: 127.0,
                highestGrade: null,
                warningCount: null,
                cautionCount: null,
                thumbnailUrl: null,
              },
            ],
          },
        } satisfies ApiResponse<unknown>),
      ),
    );

    const result = await mapApi.getFacilityLocations();

    expect(result[0].category).toBe('기타');
  });

  it('facilities 배열이 비어 있으면 빈 배열을 반환한다', async () => {
    server.use(
      http.get('/api/facilities/map', () =>
        HttpResponse.json({
          success: true,
          data: { facilities: [] },
        } satisfies ApiResponse<unknown>),
      ),
    );

    const result = await mapApi.getFacilityLocations();

    expect(result).toEqual([]);
  });
});
