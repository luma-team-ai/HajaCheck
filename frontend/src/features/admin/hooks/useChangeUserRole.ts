import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { adminApi } from '../api/adminApi';
import type { AdminUserRole } from '../types';

interface ChangeUserRolePayload {
  id: number;
  role: AdminUserRole;
}

export function useChangeUserRole() {
  const queryClient = useQueryClient();

  const mutation = useMutation<{ id: number; role: AdminUserRole }, ApiError, ChangeUserRolePayload>({
    mutationFn: ({ id, role }) => adminApi.changeRole(id, role).then((res) => res.data),
    onSuccess: () => {
      // 목록 쿼리(모든 필터/페이지 조합)를 무효화해 변경된 역할이 즉시 반영되도록 한다.
      queryClient.invalidateQueries({ queryKey: ['admin', 'users'] });
    },
  });

  return {
    changeRole: mutation.mutateAsync,
    isPending: mutation.isPending,
    error: mutation.error,
    resetError: mutation.reset,
  };
}
