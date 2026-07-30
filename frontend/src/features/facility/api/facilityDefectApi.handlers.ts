import { http, HttpResponse } from 'msw';
import type { ApiResponse, PageResponse } from '../../../shared/api/types';
import type { DefectRevision } from '../../defect/types';
import {
  mockFacilityDefectAiExplanation,
  mockFacilityDefectDetailResponse,
  mockFacilityDefectRevisions,
} from '../mocks/facilityDefect.mock';
import type { FacilityDefectAiExplanation, FacilityDefectDetailResponse } from '../types';

// 하자 상세는 이제 실 백엔드 계약(GET/PATCH /api/defects/{id}*)을 그대로 목에서도 재현한다 —
// facilityDefectApi.getDetail이 매핑을 담당하므로 이 목은 raw(FacilityDefectDetailResponse) 그대로
// 반환한다. location 편집(PATCH .../location)은 목에 반영해도 다음 GET 요청부터 초기화되므로
// (mutable 저장소를 두지 않음) 입력값을 그대로 되돌려주는 정도로만 재현한다.
export const facilityDefectHandlers = [
  http.get('/api/defects/:id', () => {
    const body: ApiResponse<FacilityDefectDetailResponse> = {
      success: true,
      data: mockFacilityDefectDetailResponse,
    };
    return HttpResponse.json(body);
  }),

  http.patch('/api/defects/:id/location', async ({ request }) => {
    const { location } = (await request.json()) as { location: string | null };
    const normalized = location == null || location.trim() === '' ? null : location;
    const body: ApiResponse<FacilityDefectDetailResponse> = {
      success: true,
      data: { ...mockFacilityDefectDetailResponse, location: normalized },
    };
    return HttpResponse.json(body);
  }),

  // GET /api/defects/:id/revisions — 정본 ActivityHistoryPanel(features/defect)이 호출하는 실
  // 백엔드 엔드포인트(DefectController.getRevisions)를 그대로 재현한다. facility 화면 전용
  // /api/facilities/:id/defect-detail/activity(백엔드 미구현, prod 404 원인)는 폐기했다(#1351).
  http.get('/api/defects/:id/revisions', ({ request }) => {
    const url = new URL(request.url);
    const page = Number(url.searchParams.get('page') ?? '0');
    const size = Number(url.searchParams.get('size') ?? '20');
    const content = mockFacilityDefectRevisions.slice(page * size, page * size + size);
    const body: ApiResponse<PageResponse<DefectRevision>> = {
      success: true,
      data: { content, page, totalElements: mockFacilityDefectRevisions.length },
    };
    return HttpResponse.json(body);
  }),

  // ai-server AIResponse({success,data,usage,error}) — 스프링 인증 프록시 /api/ai/* 경유(vite.config.ts 프록시)
  http.post('/api/ai/facility-defect-explain', () => {
    const body: { success: boolean; data: FacilityDefectAiExplanation; usage: { tokens: number } } = {
      success: true,
      data: mockFacilityDefectAiExplanation,
      usage: { tokens: 0 },
    };
    return HttpResponse.json(body);
  }),
];