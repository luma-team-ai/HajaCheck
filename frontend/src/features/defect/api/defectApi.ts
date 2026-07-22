import { api } from '../../../shared/api/axios';
import { aiClient } from '../../../shared/api/aiClient';

type DefectExplainRequest = {
  defect_type: string;
  severity_grade: string;
  location: string;
  facility_type: string;
};

type DefectExplain = {
  cause: string;
  risk: string;
  action: string;
};

export type DefectDetail = {
  id: number;
  inspectionId: number;
  type: string;
  bboxX: number | null;
  bboxY: number | null;
  bboxW: number | null;
  bboxH: number | null;
  confidence: number;
  grade: string | null;
  status: string;
  reviewed: boolean;
  deleted: boolean;
  crackWidthMm: number | null;
  crackLengthMm: number | null;
  createdAt: string;
  facilityType: string;
};

export const defectApi = {
  // GET /api/defects/{id} — 하자 단건 조회
  get: (id: string | number) => api.get<DefectDetail>(`/defects/${id}`),
  // POST /api/ai/defect-explain — AI 하자 원인·조치방안 설명
  getExplanation: (req: DefectExplainRequest) =>
    aiClient.post<DefectExplain>('/defect-explain', req),
};
