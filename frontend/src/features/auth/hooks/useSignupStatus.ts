import { useQuery } from '@tanstack/react-query';
import { authApi } from '../api/authApi';

// 가입 승인 대기 화면 — "가입 상태 새로고침" 버튼으로 수동 refetch(자동 폴링 아님, 스펙상 수동 확인)
export function useSignupStatus(signupToken: string | null) {
  const query = useQuery({
    queryKey: ['auth', 'company-signup-status', signupToken],
    queryFn: () => authApi.getSignupStatus(signupToken!).then((res) => res.data),
    enabled: !!signupToken,
    retry: false,
  });

  return {
    status: query.data,
    isLoading: query.isLoading,
    isError: query.isError,
    error: query.error,
    refetch: query.refetch,
    isRefetching: query.isRefetching,
  };
}
