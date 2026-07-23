import { useMutation } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { authApi } from '../api/authApi';
import type { BusinessVerificationRequest, BusinessVerificationResponse } from '../types';

// 회원가입 폼 "진위확인" 버튼 — 서버 상태(React Query)로 결과 관리(useEmailAvailability와 동일 골격, #663)
export function useBusinessVerification() {
  const mutation = useMutation<BusinessVerificationResponse, ApiError, BusinessVerificationRequest>({
    mutationFn: (body) => authApi.verifyBusiness(body).then((res) => res.data),
  });

  return {
    verify: mutation.mutate,
    isPending: mutation.isPending,
    result: mutation.data,
    error: mutation.error,
    reset: mutation.reset,
  };
}
