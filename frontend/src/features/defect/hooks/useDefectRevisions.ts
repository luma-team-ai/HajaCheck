import { useQuery } from '@tanstack/react-query';
import { defectApi } from '../api/defectApi';

// 하자 상세 화면 활동 기록 타임라인(HAJA-314) — id가 아직 없을 때는 요청을 보내지 않는다(useDefect와 동일 패턴).
// page는 0-based(Spring Data 관례) — 역행/건너뛰기 전이가 반복되면 이력이 페이지 크기를 넘을 수 있어
// (self-review 발견) 페이지 단위 조회를 지원한다.
export function useDefectRevisions(defectId: number | undefined, page = 0) {
  return useQuery({
    queryKey: ['defect', 'revisions', defectId ?? -1, page] as const,
    queryFn: () => defectApi.getRevisions(defectId as number, page).then((res) => res.data),
    enabled: defectId != null && !Number.isNaN(defectId),
  });
}
