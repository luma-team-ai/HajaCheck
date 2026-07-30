import { http, HttpResponse } from 'msw';
import type { ApiResponse } from '../../../shared/api/types';
import { COMPANY_OPTIONS } from '../constants';
import type { CompanyOption } from '../types';

// 실 백엔드 GET /api/platform-admin/companies(#576 구현 완료) 목 — 로컬 개발/테스트에서는
// COMPANY_OPTIONS(가짜 5곳)를 그대로 응답한다.
export const platformAdminCompanyHandlers = [
  http.get('/api/platform-admin/companies', () => {
    const body: ApiResponse<CompanyOption[]> = { success: true, data: COMPANY_OPTIONS };
    return HttpResponse.json(body);
  }),
];
