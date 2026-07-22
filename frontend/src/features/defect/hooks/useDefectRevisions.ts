import { useQuery } from '@tanstack/react-query';
import { defectApi } from '../api/defectApi';

// 하자 상세 화면 활동 기록 타임라인(HAJA-314) — id가 아직 없을 때는 요청을 보내지 않는다(useDefect와 동일 패턴).
export function useDefectRevisions(defectId: number | undefined) {
  return useQuery({
    queryKey: ['defect', 'revisions', defectId ?? -1] as const,
    queryFn: () => defectApi.getRevisions(defectId as number).then((res) => res.data),
    enabled: defectId != null && !Number.isNaN(defectId),
  });
}
