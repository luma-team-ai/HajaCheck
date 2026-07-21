import { useMutation } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { inspectionApi } from '../api/inspectionApi';
import type { InspectionCreateRequest, InspectionCreateResponse } from '../types';

export function useCreateInspection() {
  const mutation = useMutation<InspectionCreateResponse, ApiError, InspectionCreateRequest>({
    mutationFn: (body) => inspectionApi.create(body).then((res) => res.data),
  });

  return {
    createInspection: mutation.mutateAsync,
    isPending: mutation.isPending,
    error: mutation.error,
    // 폼 재입력 시 이전 실패의 에러 메시지가 남아있지 않도록 호출부에서 초기화용으로 사용
    resetError: mutation.reset,
  };
}
