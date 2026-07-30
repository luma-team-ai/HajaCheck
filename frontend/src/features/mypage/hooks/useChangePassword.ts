import { useCallback } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { mypageApi } from '../api/mypageApi';
import type { PasswordChangeRequest } from '../types';

// mutationKey를 부여해 이 mutation만 겨냥해 MutationCache에서 제거할 수 있게 한다(아래 참고).
const CHANGE_PASSWORD_MUTATION_KEY = ['mypage', 'changePassword'] as const;

// 비밀번호 변경(#1316, HAJA-602) — 마이페이지 "내 정보" 섹션. usePasswordReset(auth) 구조를 그대로
// 따른다. 성공 시 서버가 현재 세션을 무효화하므로, 호출부(PasswordChangeSection)가 안내 노출 후
// 클라이언트 세션 정리 + 재로그인 유도를 담당한다(이 훅은 요청/상태만 다룬다).
//
// mutation.variables(currentPassword/newPassword 평문)는 react-query 기본 gcTime(5분) 동안
// MutationCache에 남는다(보안 리뷰 P3). useLogout의 removeQueries는 query 캐시 전용이라 mutation은
// 건드리지 않으므로, 이 훅이 두 가지 정리 함수를 노출한다:
// - removeCachedVariables: MutationCache에서 이 mutationKey 엔트리만 제거한다.
//   MutationCache.remove()는 캐시의 내부 추적 Set에서만 제거할 뿐 현재 마운트된 관찰자(observer)의
//   렌더 상태(error/isSuccess 등)에는 영향이 없다 — 그래서 화면에 에러 메시지를 계속 보여줘야 하는
//   "사용자가 페이지에 머무는" 에러 경로(401 현재비번불일치/400/429/500)에서도 안전하게 호출할 수
//   있다(P3-2, 에러 settle 시 호출).
// - clearSensitiveState: 위에 더해 `mutation.reset()`으로 로컬 관찰자 상태(error/isSuccess)까지
//   idle로 되돌린다 — 화면을 떠나는(로그아웃·리다이렉트) 경로 전용이다. 사용자가 화면에 머무는
//   경로에서 이걸 쓰면 방금 띄운 에러 메시지가 리다이렉트 없이 즉시 사라지는 회귀가 난다(P2-1).
export function useChangePassword() {
  const queryClient = useQueryClient();
  const mutation = useMutation<null, ApiError, PasswordChangeRequest>({
    mutationKey: CHANGE_PASSWORD_MUTATION_KEY,
    mutationFn: (body) => mypageApi.changePassword(body).then((res) => res.data),
  });

  const removeCachedVariables = useCallback(() => {
    const mutationCache = queryClient.getMutationCache();
    mutationCache
      .findAll({ mutationKey: CHANGE_PASSWORD_MUTATION_KEY })
      .forEach((cachedMutation) => mutationCache.remove(cachedMutation));
  }, [queryClient]);

  const { reset } = mutation;
  const clearSensitiveState = useCallback(() => {
    reset();
    removeCachedVariables();
  }, [reset, removeCachedVariables]);

  return {
    changePassword: mutation.mutate,
    isPending: mutation.isPending,
    isSuccess: mutation.isSuccess,
    error: mutation.error,
    clearSensitiveState,
    removeCachedVariables,
  };
}
