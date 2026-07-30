import { useMutation } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { authApi } from '../../auth/api/authApi';
import { useAuthStore } from '../../auth/store/authStore';
import type { LoginRequest, UserResponse } from '../../auth/types';
import type { ApiError } from '../../../shared/api/types';
import { isCounselorRole } from '../../../shared/constants/roles';
import { COUNSELOR_QUEUE_ROUTE } from '../../../shared/constants/routes';

// 상담원 전용 로그인 — usePlatformAdminLogin과 동일 패턴(#535)을 그대로 따른다. 로그인 자체는
// 성공(백엔드가 세션 쿠키를 발급)해도 role이 COUNSELOR가 아니면 authStore에 절대 커밋하지 않고
// (setUser 미호출), 이미 발급된 세션을 authApi.logout()으로 즉시 무효화한다 — 세션을 살려두지 않는다.
export function useCounselorLogin() {
  const navigate = useNavigate();
  const setUser = useAuthStore((state) => state.setUser);
  const clearUser = useAuthStore((state) => state.clearUser);
  const [roleDenied, setRoleDenied] = useState(false);
  // logout() 실패는 서버 세션이 살아있는 채로 남을 수 있는 경우다 — usePlatformAdminLogin과 동일
  // 이유로 조용히 무시하지 않고 관측 가능하게 만든다(PR머신 리뷰 P3, #558).
  const [logoutFailed, setLogoutFailed] = useState(false);

  const mutation = useMutation<UserResponse, ApiError, LoginRequest>({
    mutationFn: (body) => authApi.login(body).then((res) => res.data),
    onMutate: () => {
      setRoleDenied(false);
      setLogoutFailed(false);
    },
    onSuccess: async (user) => {
      if (!isCounselorRole(user.role)) {
        setRoleDenied(true);
        try {
          await authApi.logout();
        } catch (logoutError) {
          setLogoutFailed(true);
          console.error(
            '[useCounselorLogin] role 불일치 사용자의 세션 무효화(logout)에 실패했습니다 — 서버 세션이 남아있을 수 있습니다.',
            logoutError,
          );
        }
        clearUser();
        return;
      }
      setUser(user);
      navigate(COUNSELOR_QUEUE_ROUTE);
    },
  });

  return {
    login: mutation.mutate,
    isPending: mutation.isPending,
    error: mutation.error,
    roleDenied,
    logoutFailed,
  };
}
