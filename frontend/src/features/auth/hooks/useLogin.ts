import { useMutation } from '@tanstack/react-query';
import { useLocation, useNavigate } from 'react-router-dom';
import type { ApiError } from '../../../shared/api/types';
import { isCounselorRole } from '../../../shared/constants/roles';
import { COUNSELOR_QUEUE_ROUTE } from '../../../shared/constants/routes';
import { isSafeInternalPath } from '../../../shared/utils/safeInternalPath';
import { authApi } from '../api/authApi';
import { useInspectionStore } from '../../inspection/store/inspectionStore';
import { useAuthStore } from '../store/authStore';
import type { LoginRequest, UserResponse } from '../types';

export function useLogin() {
  const navigate = useNavigate();
  const location = useLocation();
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
      // ProtectedRoute(shared/components/ProtectedRoute.tsx:33)가 비로그인 접근 시 원래 목적지를
      // state.from으로 보존해 /login으로 보낸다. 이 폼 로그인 경로가 그 값을 무시하고 항상
      // '/dashboard'로 고정 이동해, 세션 만료 후 로그인한 사용자가 원래 보던 화면으로 돌아가지
      // 못하던 문제(#1442). state.from은 라우터 state에 실린 값이라 외부에서 임의로 주입 가능하므로,
      // LoginPage.tsx:63-69와 동일하게 isSafeInternalPath로 내부 절대경로임을 검증한 뒤에만 사용한다
      // (검증 없이 그대로 넘기면 오픈 리다이렉트로 악용될 수 있다 — #280 P3와 동일한 위험).
      const from = (location.state as { from?: string } | null)?.from;
      // 상담원 콘솔(#1001, HAJA-495) — COUNSELOR는 일반 대시보드에 볼일이 없어 대기열로 바로 보낸다.
      const fallback = isCounselorRole(user.role) ? COUNSELOR_QUEUE_ROUTE : '/dashboard';
      navigate(isSafeInternalPath(from) ? from : fallback);
    },
  });

  return {
    login: mutation.mutate,
    isPending: mutation.isPending,
    error: mutation.error,
  };
}
