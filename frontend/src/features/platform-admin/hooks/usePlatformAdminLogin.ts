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
  // logout() 실패는 서버 세션이 살아있는 채로 남을 수 있는 경우다 — authStore엔 커밋되지 않으니
  // 이 브라우저에서 즉시 권한 상승으로 이어지진 않지만, "세션을 살려두지 않는다"는 요구사항이
  // 깨질 수 있어 조용히 무시하지 않고 관측 가능하게 만든다(PR머신 리뷰 P3, #558).
  const [logoutFailed, setLogoutFailed] = useState(false);

  const mutation = useMutation<UserResponse, ApiError, LoginRequest>({
    mutationFn: (body) => authApi.login(body).then((res) => res.data),
    onMutate: () => {
      setRoleDenied(false);
      setLogoutFailed(false);
    },
    onSuccess: async (user) => {
      if (!isPlatformAdminRole(user.role)) {
        setRoleDenied(true);
        try {
          await authApi.logout();
        } catch (logoutError) {
          setLogoutFailed(true);
          console.error(
            '[usePlatformAdminLogin] role 불일치 사용자의 세션 무효화(logout)에 실패했습니다 — 서버 세션이 남아있을 수 있습니다.',
            logoutError,
          );
        }
        // 클라이언트는 setUser를 호출하지 않아 authStore 기준으로는 인증 상태로 취급되지 않는다.
        // logout 실패 여부와 무관하게 클라이언트 상태는 항상 정리한다.
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
    logoutFailed,
  };
}
