import { useMutation } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import type { ApiError } from '../../../shared/api/types';
import { authApi } from '../api/authApi';
import type { PasswordResetRequest } from '../types';

export function usePasswordReset() {
  const navigate = useNavigate();

  const mutation = useMutation<null, ApiError, PasswordResetRequest>({
    mutationFn: (body) => authApi.passwordReset(body).then((res) => res.data),
    onSuccess: () => {
      navigate('/login');
    },
  });

  return {
    resetPassword: mutation.mutate,
    isPending: mutation.isPending,
    error: mutation.error,
  };
}
