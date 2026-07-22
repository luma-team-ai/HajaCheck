import { useMutation } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { authApi } from '../../auth/api/authApi';
import { useAuthStore } from '../../auth/store/authStore';
import type { LoginRequest, UserResponse } from '../../auth/types';
import type { ApiError } from '../../../shared/api/types';
import { isPlatformAdminRole } from '../../../shared/constants/roles';
import { PLATFORM_ADMIN_ROUTE } from '../../../shared/constants/routes';

// 플랫폼 관리자 로그인(#535) — 기존 useLogin은 role 검사 없이 무조건 setUser + navigate('/dashboard')라
// 그대로 재사용할 수 없다. 로그인 자체는 성공(백엔드가 세션 쿠키를 발급)해도 role이 PLATFORM_ADMIN이
// 아니면 authStore에 절대 커밋하지 않고(setUser 미호출), 이미 발급된 세션을 authApi.logout()으로
// 즉시 무효화한다 — 세션을 살려두지 않는다(이슈 요구사항).
export function usePlatformAdminLogin() {
  const navigate = useNavigate();
  const setUser = useAuthStore((state) => state.setUser);
  const clearUser = useAuthStore((state) => state.clearUser);
  const [roleDenied, setRoleDenied] = useState(false);

  const mutation = useMutation<UserResponse, ApiError, LoginRequest>({
    mutationFn: (body) => authApi.login(body).then((res) => res.data),
    onMutate: () => {
      setRoleDenied(false);
    },
    onSuccess: async (user) => {
      if (!isPlatformAdminRole(user.role)) {
        setRoleDenied(true);
        try {
          await authApi.logout();
        } catch {
          // 무시 — 클라이언트는 어차피 setUser를 호출하지 않아 인증 상태로 취급되지 않는다
        }
        clearUser();
        return;
      }
      setUser(user);
      navigate(PLATFORM_ADMIN_ROUTE);
    },
  });

  return {
    login: mutation.mutate,
    isPending: mutation.isPending,
    error: mutation.error,
    roleDenied,
  };
}
