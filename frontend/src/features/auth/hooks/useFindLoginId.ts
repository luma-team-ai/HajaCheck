import { useMutation } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { authApi } from '../api/authApi';
import type { IdInquiryRequest, IdInquiryResponse } from '../types';

export function useFindLoginId() {
  const mutation = useMutation<IdInquiryResponse, ApiError, IdInquiryRequest>({
    mutationFn: (body) => authApi.findLoginId(body).then((res) => res.data),
  });

  return {
    findLoginId: mutation.mutate,
    isPending: mutation.isPending,
    result: mutation.data,
    error: mutation.error,
  };
}
