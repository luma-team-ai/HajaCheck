import { useQuery } from '@tanstack/react-query';
import { defectApi } from '../api/defectApi';

type DefectExplainParams = {
  defect_type: string;
  severity_grade: string;
  location: string;
  facility_type: string;
};

// AI 실패가 하자 상세 페이지의 비-AI 기능을 막지 않아야 함 — 이 훅의 에러는
// DefectExplainPanel 내부에서만 폴백 처리하고 다른 정보와 독립적으로 동작한다.
export function useDefectExplain(params: DefectExplainParams) {
  return useQuery({
    queryKey: ['defect-explain', params],
    queryFn: () => defectApi.getExplanation(params).then((res) => res.data),
    retry: 1,
    enabled: !!params.defect_type && !!params.severity_grade && !!params.location && !!params.facility_type,
  });
}
