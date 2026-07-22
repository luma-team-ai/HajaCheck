import { useQuery } from '@tanstack/react-query';
import { defectApi } from '../../defect/api/defectApi';

type Params = {
  defectType: string;
  grade: string;
  facilityType: string;
};

// ponytail: 지시 4번 우측패널 AI설명 + 중복제거. defect/facility 두곳 이미 있으므로
// 여기선 로컬 재사용(promotion은 나중 duplication 압박이 들어올 때 — 지금은 3곳만에서도 과할 정도).
// 실패가 하자 상세 보기를 막지 않도록(React_코드_컨벤션.md §6): 이 훅의 에러는
// InspectionDefectExplainPanel 내부에서만 폴백 처리한다.
export function useInspectionDefectExplain({ defectType, grade, facilityType }: Params) {
  return useQuery({
    queryKey: ['inspection-defect-explain', defectType, grade, facilityType],
    queryFn: () =>
      defectApi
        .getExplanation({
          defect_type: defectType,
          severity_grade: grade,
          location: `${facilityType} 시설`,
          facility_type: facilityType,
        })
        .then((res) => res.data),
    retry: 1,
    enabled: Boolean(defectType) && Boolean(grade) && Boolean(facilityType),
  });
}
