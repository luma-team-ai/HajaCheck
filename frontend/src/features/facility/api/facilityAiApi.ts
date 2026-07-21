import { aiClient } from '../../../shared/api/aiClient';
import type { FacilityDefectAiExplanation } from '../types';

interface FacilityDefectAiExplainRequest {
  defectId: number;
  defectType: string;
  grade: string;
  location: string;
}

export const facilityAiApi = {
  // POST /api/ai/facility-defect-explain — AI 하자 진단·권장조치 설명 (dev-04-02, #489)
  getDefectExplanation: (req: FacilityDefectAiExplainRequest) =>
    aiClient.post<FacilityDefectAiExplanation>('/facility-defect-explain', req),
};