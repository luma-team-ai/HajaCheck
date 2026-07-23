import { api } from '../../../shared/api/axios';

export interface ReportDetailResponse {
  id: number;
  inspectionId: number;
  version: number;
  content: Record<string, unknown>;
  status: 'DRAFT' | 'FINALIZED';
  groundingCheckPassed?: boolean | null;
  pdfUrl?: string | null;
  editedBy?: number | null;
  createdBy: number;
  createdAt: string;
}

export const reportApi = {
  // 보고서 초안 생성
  generateReportDraft: (inspectionId: number) =>
    api.post<ReportDetailResponse>(`/inspections/${inspectionId}/reports`, {}),
};
