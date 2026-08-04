import { api } from '../../../shared/api/axios';
import { AI_TIMEOUT_MS, uploadTimeoutMs } from '../../../shared/api/timeouts';
import type { PageResponse } from '../../../shared/api/types';
import type { ReportFacilityOption, ReportListFilters, ReportListItem, ReportListSummary } from '../types';

export interface ReportContext {
  facility?: ReportFacilityContext | null;
  inspection?: ReportInspectionContext | null;
  company?: ReportCompanyContext | null;
  assignedInspector?: ReportUserContext | null;
  createdByUser?: ReportUserContext | null;
  defects: ReportDefectContext[];
  media: ReportMediaContext[];
}

export interface ReportFacilityContext {
  id: number;
  name: string;
  type?: string | null;
  address?: string | null;
  latitude?: number | null;
  longitude?: number | null;
  builtYear?: number | null;
  scale?: string | null;
  inspectionCycleMonths?: number | null;
  nextInspectionDueAt?: string | null;
  initialGrade?: string | null;
  assigneeUserId?: number | null;
  memo?: string | null;
  thumbnailUrl?: string | null;
}

export interface ReportInspectionContext {
  id: number;
  facilityId: number;
  createdBy: number;
  assignedInspectorId: number;
  roundNo: number;
  inspectionDate: string;
  type: string;
  status: string;
  createdAt: string;
}

export interface ReportCompanyContext {
  id: number;
  name: string;
  representativeName?: string | null;
  address?: string | null;
  addressDetail?: string | null;
}

export interface ReportUserContext {
  id: number;
  name: string;
  role?: string | null;
}

export interface ReportDefectContext {
  id: number;
  type: string;
  typeLabel?: string | null;
  grade?: string | null;
  status: string;
  location?: string | null;
  confidence?: number | null;
  mediaId?: number | null;
  bboxX?: number | null;
  bboxY?: number | null;
  bboxW?: number | null;
  bboxH?: number | null;
  crackWidthMm?: number | null;
  crackLengthMm?: number | null;
  areaRatio?: number | null;
  actionContent?: string | null;
  actionDate?: string | null;
  actionAssigneeId?: number | null;
}

export interface ReportMediaContext {
  id: number;
  inspectionId?: number | null;
  facilityId?: number | null;
  fileType?: string | null;
  thumbnailUrl?: string | null;
  detailUrl?: string | null;
  originalFilename?: string | null;
  capturedAt?: string | null;
}

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
  // #1479 후속 — "저장됨" 경과 시간 표시를 createdAt(불변)이 아니라 마지막 편집 시각으로 보여주기 위해 추가.
  // optional인 이유: 이미 캐시/테스트 픽스처에 남아있는 구 형태 응답(백엔드 배포 전)과의 호환 —
  // 소비부(ReportGeneratePage)는 없으면 createdAt으로 폴백한다.
  updatedAt?: string;
  context?: ReportContext | null;
}

export interface ReportSummaryResponse {
  id: number;
  inspectionId: number;
  version: number;
  status: 'DRAFT' | 'FINALIZED';
  groundingCheckPassed?: boolean | null;
  createdAt: string;
  /** GET /api/inspections/{id}/reports 응답 — 백엔드 DTO에 미포함될 수 있어 빠진 경우 UI에서 '알 수 없음'으로 표시. */
  createdByName?: string;
}

export const reportApi = {
  // 보고서 초안 생성 — sections/includePhoto는 사용자가 선택한 설정 옵션
  generateReportDraft: (
    inspectionId: number,
    options?: { sections: string[]; includePhoto: boolean },
    signal?: AbortSignal,
  ) =>
    api.post<ReportDetailResponse>(
      `/inspections/${inspectionId}/reports`,
      options ?? {},
      {
        signal,
        // 이름만 봐서는 AI 호출인 줄 모르는 경로다 — 백엔드 ReportService.generateDraft가 ai-server의
        // 보고서 생성 체인을 동기로 호출하므로 스프링 ai.server read-timeout 150초가 그대로 걸린다.
        // 전역 30초면 정상 생성이 끊긴다(#1598, 근거는 timeouts.ts).
        timeout: AI_TIMEOUT_MS,
      },
    ),

  // 점검별 보고서 목록 조회 (최근 작업 내역용)
  listReports: (inspectionId: number, signal?: AbortSignal) =>
    api.get<ReportSummaryResponse[]>(`/inspections/${inspectionId}/reports`, { signal }),

  // 보고서 상세 조회
  getReport: (reportId: number, signal?: AbortSignal) =>
    api.get<ReportDetailResponse>(`/reports/${reportId}`, { signal }),

  // 보고서 복제 — 같은 inspection의 다음 버전 DRAFT를 생성한다.
  cloneReport: (reportId: number, signal?: AbortSignal) =>
    api.post<ReportDetailResponse>(`/reports/${reportId}/clone`, undefined, { signal }),

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
    return api.post<{ pdfUrl: string }>(`/reports/${reportId}/pdf`, formData, {
      signal,
      // 클라이언트가 생성한 PDF 본문 전송 시간이 전역 30초를 잠식한다(사진이 많은 보고서일수록
      // 커진다) — 크기에 비례한 상한을 준다(#1598, 근거는 timeouts.ts).
      timeout: uploadTimeoutMs(file.size),
    });
  },

  // 확정 — groundingCheckPassed !== true 면 서버가 거부한다.
  finalizeReport: (reportId: number, pdfUrl: string, signal?: AbortSignal) =>
    api.post<ReportDetailResponse>(`/reports/${reportId}/finalize`, { pdfUrl }, { signal }),

  // 보고서 초안 삭제 — 서버 정책상 DRAFT만 soft delete 가능하고 FINALIZED는 거부된다.
  deleteReport: (reportId: number, signal?: AbortSignal) =>
    api.delete<void>(`/reports/${reportId}`, { signal }),

  // --- 보고서 목록 / 이력 관리 (#463, 사이드바 "보고서" 최상위 메뉴) ---------------------------
  // GET /api/reports — 회사 스코프 전체 보고서 목록(페이지네이션 + 시설물/상태/검색/기간 필터).
  // 회사 목록/요약 API가 준비되기 전까지는 hybrid에서 실 요청의 404를 훅이 목으로 폴백한다.
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
