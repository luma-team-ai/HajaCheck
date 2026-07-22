import { http, HttpResponse } from 'msw';
import type { ApiResponse } from '../../../shared/api/types';
import {
  mockFacilityDefectActivityLog,
  mockFacilityDefectAiExplanation,
  mockFacilityDefectDetail,
} from '../mocks/facilityDefect.mock';
import type {
  FacilityDefectActivityLogItem,
  FacilityDefectAiExplanation,
  FacilityDefectDetail,
} from '../types';

// "다음 단계로 전이" 버튼은 상태 mutation이 아닌 /defects/:id로의 단순 페이지 이동이라(#489 확정)
// 이 목은 조회 전용 — 상태 변경 POST 핸들러/mutable 저장소는 두지 않는다.
export const facilityDefectHandlers = [
  http.get('/api/facilities/:id/defect-detail', () => {
    const body: ApiResponse<FacilityDefectDetail> = { success: true, data: mockFacilityDefectDetail };
    return HttpResponse.json(body);
  }),

  http.get('/api/facilities/:id/defect-detail/activity', () => {
    const body: ApiResponse<FacilityDefectActivityLogItem[]> = {
      success: true,
      data: mockFacilityDefectActivityLog,
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