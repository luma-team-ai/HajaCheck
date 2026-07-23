import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import type { CreateUserPayload } from '../api/platformAdminUserApi';
import { platformAdminUserApi } from '../api/platformAdminUserApi';
import type { AdminUser } from '../types';

// features/admin/hooks/useCreateUser.ts(#405)를 그대로 옮긴 것(#577).
export function useCreateUser() {
  const queryClient = useQueryClient();

  const mutation = useMutation<AdminUser, ApiError, CreateUserPayload>({
    mutationFn: (payload) => platformAdminUserApi.createUser(payload).then((res) => res.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['platform-admin', 'users'] });
    },
  });

  return {
    createUser: mutation.mutateAsync,
    isPending: mutation.isPending,
    error: mutation.error,
    resetError: mutation.reset,
  };
}
