import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { defectApi } from '../api/defectApi';
import { inspectionDefectsKeys } from './useInspectionDefects';
import { defectKeys } from './useDefects';
import { defectRevisionsKeys } from './useDefectRevisions';
import type { Defect, DefectStatus } from '../types';

interface ChangeDefectStatusVariables {
  status: DefectStatus;
  reason: string;
}

// 하자 상세 모달의 역행/건너뛰기 상태 변경(HAJA-349/#630 사유 UI 재통합) — PATCH /api/defects/{id}/status.
// 정방향 1단계 전이는 DefectActionForm(조치 등록, PATCH /action)이 이미 처리하므로 이 훅은
// REASON_REQUIRED_TARGETS(항상 사유 필요)로만 호출된다. useSubmitDefectAction과 동일하게 상세
// 캐시를 갱신하고 카드 그리드(useInspectionDefects)를 무효화한다.
export function useChangeDefectStatus(defectId: number | undefined, inspectionId: number | undefined) {
  const queryClient = useQueryClient();

  const mutation = useMutation<Defect, ApiError, ChangeDefectStatusVariables>({
    mutationFn: ({ status, reason }) =>
      defectApi.updateStatus(defectId as number, status, reason).then((res) => res.data),
    onSuccess: (updated) => {
      if (defectId != null) {
        queryClient.setQueryData(defectKeys.detail(defectId), updated);
        // 상태 전이는 항상 defect_revisions에 이력이 남으므로(updateStatus), 활동 기록 패널이
        // 재조회 없이도 즉시 최신 내역을 보여주도록 함께 무효화한다(#1553).
        queryClient.invalidateQueries({ queryKey: defectRevisionsKeys.byDefect(defectId) });
      }
      if (inspectionId != null) {
        queryClient.invalidateQueries({ queryKey: inspectionDefectsKeys.byInspection(inspectionId) });
      }
    },
  });

  return {
    changeStatus: mutation.mutateAsync,
    isPending: mutation.isPending,
    error: mutation.error,
    resetError: mutation.reset,
  };
}
