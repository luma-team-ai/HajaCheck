import { aiClient } from '../../../shared/api/aiClient';
import type { FacilityDefectAiExplanation } from '../types';

interface FacilityDefectAiExplainRequest {
  defectId: number;
  defectType: string;
  grade: string;
  location: string;
  facilityType: string;
}

// 스프링 프록시 DTO(backend/.../ai/dto/DefectExplainRequest.java)가 location을 @NotBlank로
// 강제한다 — 빈 문자열을 그대로 보내면 400(위치 미입력 하자는 영구히 "다시 시도" 폴백). 세부 위치가
// 아직 없는 하자에도 AI 설명을 표시하는 게 목표(#1350)라 자리표시자로 채워 계약을 만족시킨다.
const UNKNOWN_LOCATION_PLACEHOLDER = '위치 정보 없음';

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
      location: req.location.trim() === '' ? UNKNOWN_LOCATION_PLACEHOLDER : req.location,
      facility_type: req.facilityType,
    }),
};