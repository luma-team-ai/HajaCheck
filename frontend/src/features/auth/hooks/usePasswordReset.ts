import { useMutation } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { authApi } from '../api/authApi';
import type { PasswordResetRequest, PasswordResetResponse } from '../types';

// 비밀번호 찾기 2단계 — #301(HAJA-224)
export function usePasswordReset() {
  const mutation = useMutation<PasswordResetResponse, ApiError, PasswordResetRequest>({
    mutationFn: (body) => authApi.resetPassword(body).then((res) => res.data),
  });

  return {
    resetPassword: mutation.mutate,
    isPending: mutation.isPending,
    isSuccess: mutation.isSuccess,
    error: mutation.error,
  };
}
