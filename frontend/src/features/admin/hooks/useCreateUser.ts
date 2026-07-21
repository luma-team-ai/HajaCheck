import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import type { CreateUserPayload } from '../api/adminApi';
import { adminApi } from '../api/adminApi';
import type { AdminUser } from '../types';

export function useCreateUser() {
  const queryClient = useQueryClient();

  const mutation = useMutation<AdminUser, ApiError, CreateUserPayload>({
    mutationFn: (payload) => adminApi.createUser(payload).then((res) => res.data),
    onSuccess: () => {
      // 목록 쿼리(모든 필터/페이지 조합)를 무효화해 새로 등록한 사용자가 즉시 반영되도록 한다.
      queryClient.invalidateQueries({ queryKey: ['admin', 'users'] });
    },
  });

  return {
    createUser: mutation.mutateAsync,
    isPending: mutation.isPending,
    error: mutation.error,
    resetError: mutation.reset,
  };
}
