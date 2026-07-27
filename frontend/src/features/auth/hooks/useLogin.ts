import { useMutation } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import type { ApiError } from '../../../shared/api/types';
import { isCounselorRole } from '../../../shared/constants/roles';
import { COUNSELOR_QUEUE_ROUTE } from '../../../shared/constants/routes';
import { authApi } from '../api/authApi';
import { useAuthStore } from '../store/authStore';
import type { LoginRequest, UserResponse } from '../types';

export function useLogin() {
  const navigate = useNavigate();
  const setUser = useAuthStore((state) => state.setUser);

  const mutation = useMutation<UserResponse, ApiError, LoginRequest>({
    mutationFn: (body) => authApi.login(body).then((res) => res.data),
    onSuccess: (user) => {
      setUser(user);
      // 상담원 콘솔(#1001, HAJA-495) — COUNSELOR는 일반 대시보드에 볼일이 없어 대기열로 바로 보낸다.
      navigate(isCounselorRole(user.role) ? COUNSELOR_QUEUE_ROUTE : '/dashboard');
    },
  });

  return {
    login: mutation.mutate,
    isPending: mutation.isPending,
    error: mutation.error,
  };
}
