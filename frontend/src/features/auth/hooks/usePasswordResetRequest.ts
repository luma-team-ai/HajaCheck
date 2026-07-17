import { useMutation } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { authApi } from '../api/authApi';
import type { PasswordResetLinkRequest, PasswordResetLinkResponse } from '../types';

// 비밀번호 찾기 1단계 — #301(HAJA-224). 계약상 계정 존재 여부와 무관하게 항상 동일 200을 받으므로
// 이 훅은 isSuccess만 노출하고, 호출부는 그 값으로 안내 문구를 바꾸지 않는다(계정 열거 방지).
export function usePasswordResetRequest() {
  const mutation = useMutation<PasswordResetLinkResponse, ApiError, PasswordResetLinkRequest>({
    mutationFn: (body) => authApi.requestPasswordReset(body).then((res) => res.data),
  });

  return {
    requestPasswordReset: mutation.mutate,
    isPending: mutation.isPending,
    isSuccess: mutation.isSuccess,
    error: mutation.error,
  };
}
