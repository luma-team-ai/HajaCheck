import { aiClient } from '../../../shared/api/aiClient';
import type { FacilityDefectAiExplanation } from '../types';

interface FacilityDefectAiExplainRequest {
  defectId: number;
  defectType: string;
  grade: string;
  location: string;
  facilityType: string;
}

// 스프링 프록시 DTO(backend/.../ai/dto/DefectExplainRequest.java)가 location·facility_type을
// @NotBlank로 강제한다 — 빈 문자열을 그대로 보내면 400. location은 세부 위치가 아직 없는 하자에도
// AI 설명을 표시하는 게 목표(#1350)라 자리표시자로 채운다. facility_type도 같은 이유로 자리표시자를
// 둔다 — 시설물 상세 조회(useFacility)가 실패하는 드문 경로에서도 AI 패널이 영구 빈 화면 대신
// (부정확할 수 있는) 설명이라도 보여주는 쪽이 "AI 실패가 비-AI 기능을 막지 않는다"는 원칙에 맞다
// (code-reviewer P2, PR #1364).
const UNKNOWN_LOCATION_PLACEHOLDER = '위치 정보 없음';
const UNKNOWN_FACILITY_TYPE_PLACEHOLDER = '시설물 유형 정보 없음';

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
      facility_type: req.facilityType.trim() === '' ? UNKNOWN_FACILITY_TYPE_PLACEHOLDER : req.facilityType,
    }),
};