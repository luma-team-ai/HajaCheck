import { useQuery } from '@tanstack/react-query';
import { defectApi } from '../api/defectApi';

// 다른 훅(useSubmitDefectAction/useChangeDefectStatus)이 상태 변경 성공 후 활동 기록 캐시를
// 무효화할 때도 이 접두어를 공유한다 — page를 뺀 접두어로 invalidateQueries하면 모든 페이지가 함께 갱신된다.
export const defectRevisionsKeys = {
  byDefect: (defectId: number) => ['defect', 'revisions', defectId] as const,
};

// 하자 상세 화면 활동 기록 타임라인(HAJA-314) — id가 아직 없을 때는 요청을 보내지 않는다(useDefect와 동일 패턴).
// page는 0-based(Spring Data 관례) — 역행/건너뛰기 전이가 반복되면 이력이 페이지 크기를 넘을 수 있어
// (self-review 발견) 페이지 단위 조회를 지원한다.
export function useDefectRevisions(defectId: number | undefined, page = 0) {
  return useQuery({
    queryKey: [...defectRevisionsKeys.byDefect(defectId ?? -1), page] as const,
    queryFn: () => defectApi.getRevisions(defectId as number, page).then((res) => res.data),
    enabled: defectId != null && !Number.isNaN(defectId),
  });
}
