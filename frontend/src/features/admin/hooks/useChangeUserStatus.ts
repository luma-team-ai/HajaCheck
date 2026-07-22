import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { adminApi } from '../api/adminApi';
import type { AdminUserStatus } from '../types';

interface ChangeUserStatusPayload {
  id: number;
  status: AdminUserStatus;
}

export function useChangeUserStatus() {
  const queryClient = useQueryClient();

  const mutation = useMutation<
    { id: number; status: AdminUserStatus },
    ApiError,
    ChangeUserStatusPayload
  >({
    mutationFn: ({ id, status }) => adminApi.changeStatus(id, status).then((res) => res.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'users'] });
    },
  });

  return {
    changeStatus: mutation.mutateAsync,
    isPending: mutation.isPending,
    error: mutation.error,
    resetError: mutation.reset,
  };
}
