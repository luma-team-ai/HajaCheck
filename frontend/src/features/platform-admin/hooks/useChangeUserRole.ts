import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { platformAdminUserApi } from '../api/platformAdminUserApi';
import type { AdminUserRole } from '../types';

interface ChangeUserRolePayload {
  id: number;
  role: AdminUserRole;
}

// features/admin/hooks/useChangeUserRole.ts(#405)를 그대로 옮긴 것(#577).
export function useChangeUserRole() {
  const queryClient = useQueryClient();

  const mutation = useMutation<{ id: number; role: AdminUserRole }, ApiError, ChangeUserRolePayload>({
    mutationFn: ({ id, role }) => platformAdminUserApi.changeRole(id, role).then((res) => res.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['platform-admin', 'users'] });
    },
  });

  return {
    changeRole: mutation.mutateAsync,
    isPending: mutation.isPending,
    error: mutation.error,
    resetError: mutation.reset,
  };
}
