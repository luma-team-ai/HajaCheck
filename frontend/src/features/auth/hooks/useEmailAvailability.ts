import { useMutation } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { authApi } from '../api/authApi';
import type { EmailAvailabilityResponse } from '../types';

// 회원가입 폼 "중복확인" 버튼 — 서버 상태(React Query)로 결과 관리
export function useEmailAvailability() {
  const mutation = useMutation<EmailAvailabilityResponse, ApiError, string>({
    mutationFn: (email) => authApi.checkEmailAvailability(email).then((res) => res.data),
  });

  return {
    checkEmailAvailability: mutation.mutate,
    isPending: mutation.isPending,
    result: mutation.data,
    error: mutation.error,
    reset: mutation.reset,
  };
}
