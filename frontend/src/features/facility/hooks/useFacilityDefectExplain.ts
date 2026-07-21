import { useQuery } from '@tanstack/react-query';
import { facilityAiApi } from '../api/facilityAiApi';

type Params = {
  defectId: number;
  defectType: string;
  grade: string;
  location: string;
};

// AI 실패가 하자 상세 페이지의 비-AI 기능(이미지·상태·등급 등)을 막지 않아야 함(React_코드_컨벤션.md §6) —
// 이 훅의 에러는 FacilityDefectAiExplainPanel 내부에서만 폴백 처리한다.
export function useFacilityDefectExplain({ defectId, defectType, grade, location }: Params) {
  return useQuery({
    queryKey: ['facility-defect-explain', defectId],
    queryFn: () =>
      facilityAiApi.getDefectExplanation({ defectId, defectType, grade, location }).then((res) => res.data),
    retry: 1,
    enabled: Boolean(defectId) && Boolean(defectType) && Boolean(grade) && Boolean(location),
  });
}