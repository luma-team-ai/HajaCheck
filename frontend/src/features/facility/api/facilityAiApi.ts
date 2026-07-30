import { aiClient } from '../../../shared/api/aiClient';
import type { FacilityDefectAiExplanation } from '../types';

interface FacilityDefectAiExplainRequest {
  defectId: number;
  defectType: string;
  grade: string;
  location: string;
  facilityType: string;
}

export const facilityAiApi = {
  // #1350 — /facility-defect-explain은 실제로 존재한 적 없는 엔드포인트였다(ai_router.py 공식
  // 목록에 없음, 백엔드 어디에도 라우트 없음 — 구현 시점부터 미완성 상태로 방치됨). 이미 실동작하는
  // POST /api/ai/defect-explain(ai_router.py:66, defect 기능의 useDefectExplain과 동일 계약)으로
  // 교체하고, 그 요청 스키마(snake_case: defect_type/severity_grade/location/facility_type)에
  // 맞춰 필드를 매핑한다. defectId는 이 엔드포인트가 안 쓰므로 요청 바디에서 제외한다.
  getDefectExplanation: (req: FacilityDefectAiExplainRequest) =>
    aiClient.post<FacilityDefectAiExplanation>('/defect-explain', {
      defect_type: req.defectType,
      severity_grade: req.grade,
      location: req.location,
      facility_type: req.facilityType,
    }),
};