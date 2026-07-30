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
// clearSensitiveState (보안 리뷰 P3) — mutation.variables(currentPassword/newPassword 평문)는
// react-query 기본 gcTime(5분) 동안 MutationCache에 남는다. `mutation.reset()`은 이 훅이 반환하는
// 로컬 관찰자 상태(error/isSuccess 등)만 idle로 되돌릴 뿐, MutationCache의 엔트리 자체는 지우지
// 않는다 — 그래서 mutationKey로 지정해 캐시에서 직접 찾아 제거한다. useLogout의 removeQueries는
// query 캐시 전용이라 mutation은 건드리지 않으므로, 로그아웃 직전 호출부가 이 함수로 명시 정리해야
// 한다.
export function useChangePassword() {
  const queryClient = useQueryClient();
  const mutation = useMutation<null, ApiError, PasswordChangeRequest>({
    mutationKey: CHANGE_PASSWORD_MUTATION_KEY,
    mutationFn: (body) => mypageApi.changePassword(body).then((res) => res.data),
  });

  const clearSensitiveState = () => {
    mutation.reset();
    const mutationCache = queryClient.getMutationCache();
    mutationCache
      .findAll({ mutationKey: CHANGE_PASSWORD_MUTATION_KEY })
      .forEach((cachedMutation) => mutationCache.remove(cachedMutation));
  };

  return {
    changePassword: mutation.mutate,
    isPending: mutation.isPending,
    isSuccess: mutation.isSuccess,
    error: mutation.error,
    clearSensitiveState,
  };
}
