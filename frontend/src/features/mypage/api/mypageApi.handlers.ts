import { http, HttpResponse } from 'msw';
import type { ApiResponse, PageResponse } from '../../../shared/api/types';
import {
  MOCK_MY_COUNSELS_TOTAL_ELEMENTS,
  mockMyCounselRows,
} from '../mocks/myCounsels.mock';
import {
  MOCK_MY_INSPECTIONS_TOTAL_ELEMENTS,
  mockMyInspectionRows,
  mockMyInspectionsSummary,
  mockMyReports,
} from '../mocks/myInspections.mock';
import { mockMyPlan, mockSeats } from '../mocks/mypage.mock';
import type {
  InspectionHistoryRow,
  MyCounselRow,
  MyInspectionsSummary,
  MyPlan,
  MyReportCard,
  SeatsInfo,
  UpgradeInquiryResult,
} from '../types';

export const mypageHandlers = [
  http.get('/api/me/plan', () => {
    const body: ApiResponse<MyPlan> = { success: true, data: mockMyPlan };
    return HttpResponse.json(body);
  }),

  http.get('/api/me/seats', () => {
    const body: ApiResponse<SeatsInfo> = { success: true, data: mockSeats };
    return HttpResponse.json(body);
  }),

  http.post('/api/me/plan/upgrade-inquiry', () => {
    const body: ApiResponse<UpgradeInquiryResult> = {
      success: true,
      data: { status: 'UPGRADE_REQUESTED' },
    };
    return HttpResponse.json(body);
  }),

  // 내 점검 이력 / 보고서 (HAJA-366, #668) — BE 미구현이라 page/size 쿼리 파라미터는 실제로
  // 반영하지 않고 항상 같은 8건 + totalElements=18(mock 표시용)을 반환한다.
  http.get('/api/me/inspections/summary', () => {
    const body: ApiResponse<MyInspectionsSummary> = { success: true, data: mockMyInspectionsSummary };
    return HttpResponse.json(body);
  }),

  http.get('/api/me/inspections', () => {
    const page: PageResponse<InspectionHistoryRow> = {
      content: mockMyInspectionRows,
      page: 0,
      totalElements: MOCK_MY_INSPECTIONS_TOTAL_ELEMENTS,
    };
    const body: ApiResponse<PageResponse<InspectionHistoryRow>> = { success: true, data: page };
    return HttpResponse.json(body);
  }),

  http.get('/api/me/reports', () => {
    const body: ApiResponse<MyReportCard[]> = { success: true, data: mockMyReports };
    return HttpResponse.json(body);
  }),

  // 내 상담 내역 (HAJA-371, #678) — 상담 BE API 전무. page/size 쿼리 파라미터는 실제로 반영하지
  // 않고 항상 같은 4건 + totalElements=18(mock 표시용)을 반환한다.
  http.get('/api/me/counsels', () => {
    const page: PageResponse<MyCounselRow> = {
      content: mockMyCounselRows,
      page: 0,
      totalElements: MOCK_MY_COUNSELS_TOTAL_ELEMENTS,
    };
    const body: ApiResponse<PageResponse<MyCounselRow>> = { success: true, data: page };
    return HttpResponse.json(body);
  }),
];
