import { useQueries } from '@tanstack/react-query';
import { defectApi } from '../api/defectApi';
import { formatDefectCode } from '../utils/defectFormat';
import type { Defect } from '../types';

export interface InspectionActivityItem {
  id: string;
  defectCode: string;
  fieldChanged: string;
  oldValue: string | null;
  newValue: string | null;
  reason: string | null;
  createdAt: string;
}

// 점검 상세(카드형) 우측 "활동 기록" 사이드바(contract.md §화면 구조 ②) — 활동 기록 조회
// (GET /api/defects/{id}/revisions)는 하자 단위 API뿐이라, 점검에 속한 하자 전체의 첫 페이지를
// 모아 최신순으로 합친 요약 피드로 보여준다(페이지네이션은 범위 밖 — 상세 이력은 각 하자 상세
// 모달의 활동 기록에서 확인).
export function useInspectionActivity(defects: Defect[]) {
  const queries = useQueries({
    queries: defects.map((defect) => ({
      queryKey: ['defect', 'revisions', defect.id, 0] as const,
      queryFn: () => defectApi.getRevisions(defect.id, 0).then((res) => res.data),
    })),
  });

  const isLoading = queries.length > 0 && queries.some((query) => query.isLoading);
  const isError = queries.some((query) => query.isError);

  const items: InspectionActivityItem[] = queries
    .flatMap((query, index) => {
      const defect = defects[index];
      return (query.data?.content ?? []).map((revision) => ({
        id: `${defect.id}-${revision.id}`,
        defectCode: formatDefectCode(defect.id),
        fieldChanged: revision.fieldChanged,
        oldValue: revision.oldValue,
        newValue: revision.newValue,
        reason: revision.reason,
        createdAt: revision.createdAt,
      }));
    })
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt));

  return { items, isLoading, isError };
}
