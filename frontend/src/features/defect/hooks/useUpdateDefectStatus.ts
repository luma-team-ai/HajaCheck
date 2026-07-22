import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { defectApi } from '../api/defectApi';
import type { Defect, DefectStatus } from '../types';
import { defectKeys } from './useDefects';

// 하자 상태 전이(HAJA-30, 2단계) — 성공 시 상세 쿼리를 무효화해 스텝퍼가 최신 상태를 즉시 반영하도록 한다.
export function useUpdateDefectStatus(id: number | undefined) {
  const queryClient = useQueryClient();

  const mutation = useMutation<Defect, ApiError, DefectStatus>({
    mutationFn: (status) => defectApi.updateStatus(id as number, status).then((res) => res.data),
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
