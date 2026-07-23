import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { defectApi } from '../api/defectApi';
import type { Defect, DefectStatus } from '../types';
import { defectKeys } from './useDefects';

// PATCH 요청 변수 — status만 필수, reason은 역행·건너뛰기 전이에서만 필요(조치 보드 재사용을 위해
// updateStatus(id, status)에서 updateStatus({ status, reason? }) 형태로 확장, HAJA-349/#630).
export interface UpdateDefectStatusVariables {
  status: DefectStatus;
  reason?: string;
}

// 하자 상태 전이(HAJA-30, 2단계) — 성공 시 상세 쿼리를 무효화해 스텝퍼가 최신 상태를 즉시 반영하도록 한다.
export function useUpdateDefectStatus(id: number | undefined) {
  const queryClient = useQueryClient();

  const mutation = useMutation<Defect, ApiError, UpdateDefectStatusVariables>({
    mutationFn: ({ status, reason }) =>
      defectApi.updateStatus(id as number, status, reason).then((res) => res.data),
    onSuccess: () => {
      if (id != null) {
        queryClient.invalidateQueries({ queryKey: defectKeys.detail(id) });
      }
    },
  });

  return {
    updateStatus: mutation.mutateAsync,
    isPending: mutation.isPending,
    error: mutation.error,
    resetError: mutation.reset,
  };
}
