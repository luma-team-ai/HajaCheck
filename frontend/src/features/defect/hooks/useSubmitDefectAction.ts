import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { defectApi } from '../api/defectApi';
import { inspectionDefectsKeys } from './useInspectionDefects';
import { defectKeys } from './useDefects';
import type { Defect, DefectActionSubmitRequest } from '../types';

// 하자 상세 모달 "조치 완료 등록" 제출(HAJA-394/#726) — PATCH /api/defects/{id}/action(확정,
// contract.md §"조치 결과 등록"). 성공 시 상세 캐시를 갱신하고, 같은 점검의
// 카드 그리드(useInspectionDefects)도 최신 상태를 반영하도록 무효화한다.
export function useSubmitDefectAction(defectId: number | undefined, inspectionId: number | undefined) {
  const queryClient = useQueryClient();

  const mutation = useMutation<Defect, ApiError, DefectActionSubmitRequest>({
    mutationFn: (body) => defectApi.submitAction(defectId as number, body).then((res) => res.data),
    onSuccess: (updated) => {
      if (defectId != null) {
        queryClient.setQueryData(defectKeys.detail(defectId), updated);
      }
      if (inspectionId != null) {
        queryClient.invalidateQueries({ queryKey: inspectionDefectsKeys.byInspection(inspectionId) });
      }
    },
  });

  return {
    submitAction: mutation.mutateAsync,
    isPending: mutation.isPending,
    error: mutation.error,
    resetError: mutation.reset,
  };
}
