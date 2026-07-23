import { http, HttpResponse } from 'msw';
import type { ApiResponse } from '../../../shared/api/types';
import { MEDIA_ALLOWED_TYPES, MEDIA_MAX_FILES_PER_REQUEST } from '../constants';
import type { Media } from '../types';

// 목 썸네일 — 실제 사진 대신 SVG data URI(inspectionResult.mock.ts와 동일 패턴, 피그마 시안 확정 전까지 임시).
const MOCK_THUMBNAIL_URL =
  'data:image/svg+xml;utf8,' +
  encodeURIComponent(
    '<svg xmlns="http://www.w3.org/2000/svg" width="400" height="300">' +
      '<rect width="100%" height="100%" fill="#d9d9d9"/>' +
      '<text x="50%" y="50%" font-size="20" fill="#888" text-anchor="middle">촬영 이미지 (mock)</text>' +
      '</svg>',
  );

// 데모 시설물(id=1)의 점검 회차 + 새 점검 생성 목(nextInspectionId 시작값 100)을 유효한 것으로 취급한다.
const KNOWN_INSPECTION_IDS = new Set([1, 100, 101, 102]);

let nextMediaId = 1;

export const mediaHandlers = [
  http.post('/api/inspections/:inspectionId/media', async ({ params, request }) => {
    const inspectionId = Number(params.inspectionId);

    if (!KNOWN_INSPECTION_IDS.has(inspectionId)) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'INSPECTION_NOT_FOUND', message: '점검 회차를 찾을 수 없습니다.' },
      };
      return HttpResponse.json(failure, { status: 404 });
    }

    const formData = await request.formData();
    const files = formData.getAll('files').filter((entry): entry is File => entry instanceof File);

    if (files.length === 0) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'FILE_REQUIRED', message: '파일이 필요합니다.' },
      };
      return HttpResponse.json(failure, { status: 400 });
    }

    if (files.length > MEDIA_MAX_FILES_PER_REQUEST) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: {
          code: 'MEDIA_COUNT_EXCEEDED',
          message: '한 번에 업로드할 수 있는 파일 수를 초과했습니다.',
        },
      };
      return HttpResponse.json(failure, { status: 400 });
    }

    const invalidFile = files.find((file) => !MEDIA_ALLOWED_TYPES.includes(file.type));
    if (invalidFile) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'FILE_INVALID_TYPE', message: '허용되지 않는 파일 형식입니다. (JPG, PNG만 가능)' },
      };
      return HttpResponse.json(failure, { status: 400 });
    }

    const created: Media[] = files.map((file) => ({
      id: nextMediaId++,
      inspectionId,
      fileType: 'IMAGE',
      thumbnailUrl: MOCK_THUMBNAIL_URL,
      mimeType: file.type,
      capturedAt: null,
      gpsLat: null,
      gpsLng: null,
      createdAt: new Date().toISOString(),
    }));

    const body: ApiResponse<Media[]> = { success: true, data: created };
    return HttpResponse.json(body, { status: 201 });
  }),
];
