import { api } from '../../../shared/api/axios';
import type { PageResponse } from '../../../shared/api/types';
import type { ReportFacilityOption, ReportListFilters, ReportListItem, ReportListSummary } from '../types';

export interface ReportDetailResponse {
  id: number;
  inspectionId: number;
  version: number;
  // 백엔드가 자유 JSON으로 저장하는 필드 — 실제 구조는 ai-server report_chain.py의 4섹션
  // 스키마(ReportContent, features/report/types.ts)를 따르지만 계약상 강타입을 보장하지 않으므로
  // 호출부가 isReportContent 가드로 확인한 뒤 좁혀 쓴다.
  content: unknown;
  status: 'DRAFT' | 'FINALIZED';
  groundingCheckPassed?: boolean | null;
  pdfUrl?: string | null;
  editedBy?: number | null;
  createdBy: number;
  createdAt: string;
}

export interface ReportSummaryResponse {
  id: number;
  inspectionId: number;
  version: number;
  status: 'DRAFT' | 'FINALIZED';
  groundingCheckPassed?: boolean | null;
  createdAt: string;
  createdByName?: string;
}

export const reportApi = {
  // 보고서 초안 생성
  generateReportDraft: (inspectionId: number, signal?: AbortSignal) =>
    api.post<ReportDetailResponse>(`/inspections/${inspectionId}/reports`, {}, { signal }),

  // 점검별 보고서 목록 조회 (최근 작업 내역용)
  listReports: (inspectionId: number, signal?: AbortSignal) =>
    api.get<ReportSummaryResponse[]>(`/inspections/${inspectionId}/reports`, { signal }),

  // 보고서 상세 조회
  getReport: (reportId: number, signal?: AbortSignal) =>
    api.get<ReportDetailResponse>(`/reports/${reportId}`, { signal }),

  // 보고서 본문 수정 — DRAFT 상태에서만 허용(FINALIZED면 서버가 거부).
  // 서버는 성공 시 groundingCheckPassed를 null로 리셋한 최신 상태를 반환한다.
  updateContent: (reportId: number, content: object, signal?: AbortSignal) =>
    api.patch<ReportDetailResponse>(
      `/reports/${reportId}`,
      { contentJson: JSON.stringify(content) },
      { signal },
    ),

  // 확정 검증(grounding recheck) — 편집된 detail.items를 확정 하자와 구조 비교해
  // groundingCheckPassed를 true/false로 갱신한 최신 상태를 반환한다.
  groundingRecheck: (reportId: number, signal?: AbortSignal) =>
    api.post<ReportDetailResponse>(`/reports/${reportId}/grounding-recheck`, undefined, { signal }),

  // 클라이언트에서 생성한 PDF 파일 업로드 — 서버는 PDF를 생성해주지 않는다.
  uploadPdf: (reportId: number, file: Blob, fileName: string, signal?: AbortSignal) => {
    const formData = new FormData();
    formData.append('file', file, fileName);
    return api.post<{ pdfUrl: string }>(`/reports/${reportId}/pdf`, formData, { signal });
  },

  // 확정 — groundingCheckPassed !== true 면 서버가 거부한다.
  finalizeReport: (reportId: number, pdfUrl: string, signal?: AbortSignal) =>
    api.post<ReportDetailResponse>(`/reports/${reportId}/finalize`, { pdfUrl }, { signal }),

  // --- 보고서 목록 / 이력 관리 (#463, 사이드바 "보고서" 최상위 메뉴) ---------------------------
  // GET /api/reports — 회사 스코프 전체 보고서 목록(페이지네이션 + 시설물/상태/검색/기간 필터).
  // 백엔드 신규 구현 대기 상태라 MSW 목으로 우선 개발한다(defect feature InspectionListItem과
  // 동일 선례).
  listCompanyReports: (filters: ReportListFilters = {}, signal?: AbortSignal) =>
    api.get<PageResponse<ReportListItem>>('/reports', { params: filters, signal }),

  // GET /api/reports/summary — KPI 4종(전체/완료/편집 중/이번 달 발급).
  getCompanyReportsSummary: (signal?: AbortSignal) =>
    api.get<ReportListSummary>('/reports/summary', { signal }),

  // GET /api/facilities — 목록 필터의 시설물 select 옵션. facility feature import 없이 실
  // 엔드포인트만 재사용(defect feature listFacilityOptions와 동일 패턴).
  listFacilityOptions: (signal?: AbortSignal) =>
    api.get<ReportFacilityOption[]>('/facilities', { signal }),
};
