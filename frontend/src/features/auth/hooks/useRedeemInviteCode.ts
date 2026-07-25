import { useMutation } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { authApi } from '../api/authApi';
import { useAuthStore } from '../store/authStore';
import type { InviteCodeRedeemRequest, UserResponse } from '../types';

// 초대 코드 redeem(#794 backend PR #801, #799) — 성공 시 authStore.user를 최신값(status=ACTIVE,
// companyId 배선)으로 즉시 교체한다. AuthGate의 getMe 재조회를 기다리지 않고 바로 ProtectedRoute의
// WAITING 리다이렉트 판정이 갱신되게 하기 위함(그렇지 않으면 확인 직후에도 여전히 초대 코드
// 화면으로 되돌아가는 것처럼 보일 수 있다).
export function useRedeemInviteCode() {
  const setUser = useAuthStore((state) => state.setUser);

  const mutation = useMutation<UserResponse, ApiError, InviteCodeRedeemRequest>({
    mutationFn: (body) => authApi.redeemInviteCode(body).then((res) => res.data),
    onSuccess: (user) => setUser(user),
  });

  return {
    redeemInviteCode: mutation.mutate,
    isPending: mutation.isPending,
    isSuccess: mutation.isSuccess,
    error: mutation.error,
    reset: mutation.reset,
  };
}
