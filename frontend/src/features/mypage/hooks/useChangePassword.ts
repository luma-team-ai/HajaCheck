import { useMutation } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { mypageApi } from '../api/mypageApi';
import type { PasswordChangeRequest } from '../types';

// 비밀번호 변경(#1316, HAJA-602) — 마이페이지 "내 정보" 섹션. usePasswordReset(auth) 구조를 그대로
// 따른다. 성공 시 서버가 현재 세션을 무효화하므로, 호출부(PasswordChangeSection)가 안내 노출 후
// 클라이언트 세션 정리 + 재로그인 유도를 담당한다(이 훅은 요청/상태만 다룬다).
export function useChangePassword() {
  const mutation = useMutation<null, ApiError, PasswordChangeRequest>({
    mutationFn: (body) => mypageApi.changePassword(body).then((res) => res.data),
  });

  return {
    changePassword: mutation.mutate,
    isPending: mutation.isPending,
    isSuccess: mutation.isSuccess,
    error: mutation.error,
  };
}
