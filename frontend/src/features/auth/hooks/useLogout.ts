import { useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { authApi } from '../api/authApi';
import { AUTH_ME_QUERY_KEY, LOGIN_ROUTE } from '../constants';
import { useAuthStore } from '../store/authStore';

// 로그아웃 — SideNavBar/Header가 공유하는 단일 훅 (React_코드_컨벤션.md §0 "공통 로직 중복 금지")
// logout API가 실패해도 클라이언트 세션(react-query 캐시·authStore)은 항상 정리한다 —
// 로그아웃은 사용자 관점에서 항상 성공해야 하는 액션이라, 네트워크 오류로 화면에 갇히면 안 된다.
export function useLogout() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const clearUser = useAuthStore((state) => state.clearUser);

  const logout = async (): Promise<void> => {
    try {
      await authApi.logout();
    } catch {
      // 무시 — API 실패와 무관하게 클라이언트 세션은 정리한다
    } finally {
      // queryClient.clear()는 AuthGate가 상시 구독 중인 ['auth','me'] 쿼리 옵저버까지
      // 초기화해 재-pending 상태로 되돌리고(스플래시 재노출, PR #232 P2-C), react-query가
      // 그 재구독을 즉시 재요청으로 이어가 유효 쿠키가 남아있으면 세션이 재복원되는
      // 부작용(P2-D)이 있었다 — 그래서 auth 쿼리는 지우지 않고 settled-null로 고정만 하고,
      // 그 외 캐시만 제거한다.
      queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== 'auth' });
      queryClient.setQueryData(AUTH_ME_QUERY_KEY, null);
      clearUser();
      navigate(LOGIN_ROUTE);
    }
  };

  return { logout };
}
