import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { platformAdminUserApi } from '../api/platformAdminUserApi';
import type { AdminUserStatus } from '../types';

interface ChangeUserStatusPayload {
  id: number;
  status: AdminUserStatus;
}

// features/admin/hooks/useChangeUserStatus.ts(#405)를 그대로 옮긴 것(#577).
export function useChangeUserStatus() {
  const queryClient = useQueryClient();

  const mutation = useMutation<
    { id: number; status: AdminUserStatus },
    ApiError,
    ChangeUserStatusPayload
  >({
    mutationFn: ({ id, status }) =>
      platformAdminUserApi.changeStatus(id, status).then((res) => res.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['platform-admin', 'users'] });
    },
  });

  return {
    changeStatus: mutation.mutateAsync,
    isPending: mutation.isPending,
    error: mutation.error,
    resetError: mutation.reset,
  };
}
