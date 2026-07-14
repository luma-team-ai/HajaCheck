import { useMutation } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import type { ApiError } from '../../../shared/api/types';
import { authApi } from '../api/authApi';
import { useAuthStore } from '../store/authStore';
import type { LoginRequest } from '../types';

export function useLogin() {
  const navigate = useNavigate();
  const setUser = useAuthStore((state) => state.setUser);

  const mutation = useMutation({
    mutationFn: (body: LoginRequest) => authApi.login(body).then((res) => res.data),
    onSuccess: (user) => {
      setUser(user);
      navigate('/dashboard');
    },
  });

  return {
    login: mutation.mutate,
    isPending: mutation.isPending,
    error: mutation.error as ApiError | null,
  };
}
