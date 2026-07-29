import { useMutation } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import type { ApiError } from '../../../shared/api/types';
import { isCounselorRole } from '../../../shared/constants/roles';
import { COUNSELOR_QUEUE_ROUTE } from '../../../shared/constants/routes';
import { authApi } from '../api/authApi';
import { useInspectionStore } from '../../inspection/store/inspectionStore';
import { useAuthStore } from '../store/authStore';
import type { LoginRequest, UserResponse } from '../types';

export function useLogin() {
  const navigate = useNavigate();
  const setUser = useAuthStore((state) => state.setUser);

  const mutation = useMutation<UserResponse, ApiError, LoginRequest>({
    mutationFn: (body) => authApi.login(body).then((res) => res.data),
    onSuccess: (user) => {
      // 로그인 성공 시점에 이전 세션의 activeInspectionId/activeReportId를 지운다 — 이 스토어가
      // localStorage에 영속화되므로(#1194), 공용 PC에서 다른 사용자가 로그인해도 지우지 않으면
      // 방금 로그인한 사용자의 사이드바에 이전 사용자의 회차 id가 그대로 노출된다(PR머신 리뷰 P1).
      const { clearActiveInspectionId, clearActiveReportId } = useInspectionStore.getState();
      clearActiveInspectionId();
      clearActiveReportId();
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
