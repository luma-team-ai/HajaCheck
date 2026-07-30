import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { platformAdminUserApi } from '../api/platformAdminUserApi';
import type { CounselType } from '../types';

interface ChangeUserSkillPayload {
  id: number;
  skill: CounselType;
}

// 상담원 스킬 변경(#1001, HAJA-495) — useChangeUserRole/useChangeUserStatus와 동일 패턴.
export function useChangeUserSkill() {
  const queryClient = useQueryClient();

  const mutation = useMutation<{ id: number; skill: CounselType }, ApiError, ChangeUserSkillPayload>({
    mutationFn: ({ id, skill }) => platformAdminUserApi.changeSkill(id, skill).then((res) => res.data),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: ['platform-admin', 'users'] });
      queryClient.invalidateQueries({ queryKey: ['platform-admin', 'users', variables.id, 'skills'] });
    },
  });

  return {
    changeSkill: mutation.mutateAsync,
    isPending: mutation.isPending,
    error: mutation.error,
    resetError: mutation.reset,
  };
}
