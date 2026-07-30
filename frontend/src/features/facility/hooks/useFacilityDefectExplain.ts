import { useQuery } from '@tanstack/react-query';
import { facilityAiApi } from '../api/facilityAiApi';

type Params = {
  defectId: number;
  defectType: string;
  grade: string;
  location: string;
  facilityType: string;
};

// AI 실패가 하자 상세 페이지의 비-AI 기능(이미지·상태·등급 등)을 막지 않아야 함(React_코드_컨벤션.md §6) —
// 이 훅의 에러는 FacilityDefectAiExplainPanel 내부에서만 폴백 처리한다.
//
// #1350 — location은 필수 조건에서 제외한다. 세부 위치가 아직 안 채워진 하자가 흔해(location이
// 비어있으면 AI 설명 자체가 영구히 빈 화면으로 남는 문제) defectType·grade만 있어도 AI가 유의미한
// 진단을 생성할 수 있으므로 그 둘만 필수로 남긴다. location은 있으면 요청에 그대로 실어 더 정확한
// 설명을 유도하고, 없으면 빈 문자열로 보낸다. facilityType은 실 엔드포인트(POST /ai/defect-explain)의
// 필수 필드라 항상 포함하지만, 값 자체가 없다고 조회를 막지는 않는다(빈 문자열 허용, Pydantic에
// min_length 제약 없음 확인됨).
export function useFacilityDefectExplain({ defectId, defectType, grade, location, facilityType }: Params) {
  return useQuery({
    queryKey: ['facility-defect-explain', defectId],
    queryFn: () =>
      facilityAiApi
        .getDefectExplanation({ defectId, defectType, grade, location, facilityType })
        .then((res) => res.data),
    retry: 1,
    enabled: Boolean(defectId) && Boolean(defectType) && Boolean(grade),
  });
}