import { useMutation } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { authApi } from '../../auth/api/authApi';
import { useAuthStore } from '../../auth/store/authStore';
import type { LoginRequest, UserResponse } from '../../auth/types';
import { useInspectionStore } from '../../inspection/store/inspectionStore';
import { clearRagSessionId } from '../../support/utils/ragSessionId';
import type { ApiError } from '../../../shared/api/types';
import { PLATFORM_ADMIN_ROUTE } from '../../../shared/constants/routes';

// 플랫폼 관리자 로그인(#535) — POST /api/auth/platform-admin/login(#1513, BE PR #1533).
//
// 이 엔드포인트는 서버가 PLATFORM_ADMIN만 허용한다. 그 외 role은 자격증명이 맞아도 세션을
// 발급받지 못한 채 403 AUTH_ROLE_NOT_ALLOWED로 거절되므로, 예전처럼 "로그인 성공 후 role을 보고
// authApi.logout()으로 세션을 되돌리는" 사후 처리는 되돌릴 세션 자체가 없어 삭제했다.
// onSuccess 도달 = 허용 role + 세션 발급 완료. 거절 안내는 화면(PlatformAdminLoginPage)이 error.code로 한다.
export function usePlatformAdminLogin() {
  const navigate = useNavigate();
  const setUser = useAuthStore((state) => state.setUser);

  const mutation = useMutation<UserResponse, ApiError, LoginRequest>({
    mutationFn: (body) => authApi.platformAdminLogin(body).then((res) => res.data),
    onSuccess: (user) => {
      // 로그인 성공 시점에 이전 세션의 activeInspectionId/activeReportId를 지운다 — 이 스토어는
      // localStorage에 영속화되므로(#1194), 공용 PC에서 계정이 바뀌어도 지우지 않으면 이전
      // 사용자의 회차 id가 남는다. 로그인 진입점 3곳(useLogin·이 훅·useCounselorLogin)이 같은
      // 계약을 지켜야 한다 — 한 곳만 지키면 다음 회귀 때 어느 경로가 새는지 알 수 없다.
      const { clearActiveInspectionId, clearActiveReportId } = useInspectionStore.getState();
      clearActiveInspectionId();
      clearActiveReportId();
      // RAG 챗봇 세션(localStorage 영속)도 같은 이유로 정리한다(#1590) — 진입점 3곳이 동일 계약.
      clearRagSessionId();

      setUser(user);
      navigate(PLATFORM_ADMIN_ROUTE);
    },
  });

  return {
    login: mutation.mutate,
    isPending: mutation.isPending,
    error: mutation.error,
  };
}
