import { http, HttpResponse } from 'msw';
import type { ApiResponse } from '../../../shared/api/types';
import { FACILITY_PHOTO_MAX_COUNT } from '../constants';
import type { FacilityPhoto } from '../types';

// inspection/api/mediaApi.handlers.ts와 동일 목 썸네일(SVG data URI) — 실제 사진 대신 임시.
const MOCK_THUMBNAIL_URL =
  'data:image/svg+xml;utf8,' +
  encodeURIComponent(
    '<svg xmlns="http://www.w3.org/2000/svg" width="400" height="300">' +
      '<rect width="100%" height="100%" fill="#d9d9d9"/>' +
      '<text x="50%" y="50%" font-size="20" fill="#888" text-anchor="middle">대표 사진 (mock)</text>' +
      '</svg>',
  );

let nextPhotoId = 1;
// facilityId별 등록된 대표 사진 목록 — 4장 누적 제한(FACILITY_PHOTO_COUNT_EXCEEDED)을 재현하려면
// "이번 요청"만이 아니라 이미 보유한 장수까지 알아야 하므로 모듈 스코프 저장소로 유지한다
// (inspection/api/mediaApi.handlers.ts와 달리 이쪽은 누적 검증이 필요해 Map을 둔다).
let photosByFacilityId = new Map<number, FacilityPhoto[]>();

// 테스트 격리용 — facilityApi.handlers.ts의 resetFacilityMockStore()와 동일한 이유로,
// afterEach(resetHandlers)로는 이 모듈 스코프 Map이 초기화되지 않아 명시적 리셋 함수를 노출한다.
export function resetFacilityMediaMockStore(): void {
  photosByFacilityId = new Map<number, FacilityPhoto[]>();
  nextPhotoId = 1;
}

export const facilityMediaHandlers = [
  http.post('/api/facilities/:facilityId/media', async ({ params, request }) => {
    const facilityId = Number(params.facilityId);

    const formData = await request.formData();
    // `entry instanceof File`가 아니라 문자열이 아닌지로 판별한다 — msw/node(vitest jsdom 환경)가
    // 재구성하는 FormData 엔트리는 undici의 File 클래스라서, 이 핸들러 모듈이 참조하는 jsdom의
    // File 전역과 identity가 달라 instanceof가 항상 false가 된다(constructor.name은 둘 다
    // "File"로 동일). 실제 브라우저(MSW Service Worker)에서는 단일 File 전역이라 문제 없지만,
    // 테스트 환경 호환을 위해 문자열이 아닌 엔트리(=파일 필드)로 판별한다.
    const files = formData.getAll('files').filter((entry): entry is File => typeof entry !== 'string');

    if (files.length === 0) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'FILE_REQUIRED', message: '파일이 필요합니다.' },
      };
      return HttpResponse.json(failure, { status: 400 });
    }

    const existing = photosByFacilityId.get(facilityId) ?? [];
    if (existing.length + files.length > FACILITY_PHOTO_MAX_COUNT) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: {
          code: 'FACILITY_PHOTO_COUNT_EXCEEDED',
          message: `대표 사진은 최대 ${FACILITY_PHOTO_MAX_COUNT}장까지 등록할 수 있습니다.`,
        },
      };
      return HttpResponse.json(failure, { status: 400 });
    }

    const created: FacilityPhoto[] = files.map((file) => ({
      id: nextPhotoId++,
      inspectionId: null,
      fileType: 'IMAGE',
      thumbnailUrl: MOCK_THUMBNAIL_URL,
      detailUrl: MOCK_THUMBNAIL_URL,
      mimeType: file.type,
      capturedAt: null,
      gpsLat: null,
      gpsLng: null,
      createdAt: new Date().toISOString(),
    }));
    photosByFacilityId.set(facilityId, [...existing, ...created]);

    const body: ApiResponse<FacilityPhoto[]> = { success: true, data: created };
    return HttpResponse.json(body, { status: 201 });
  }),

  http.get('/api/facilities/:facilityId/media', ({ params }) => {
    const facilityId = Number(params.facilityId);
    const body: ApiResponse<FacilityPhoto[]> = {
      success: true,
      data: photosByFacilityId.get(facilityId) ?? [],
    };
    return HttpResponse.json(body);
  }),
];
