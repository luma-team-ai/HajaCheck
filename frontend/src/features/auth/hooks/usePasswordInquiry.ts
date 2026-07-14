import { useMutation } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import type { ApiError } from '../../../shared/api/types';
import { authApi } from '../api/authApi';
import { RESET_PASSWORD_ROUTE } from '../constants';
import type { PasswordInquiryRequest, PasswordInquiryResponse } from '../types';

// 비밀번호 찾기 1단계(기업정보 인증) 성공 시 새 비밀번호 설정 화면으로 이동
export function usePasswordInquiry() {
  const navigate = useNavigate();

  const mutation = useMutation<PasswordInquiryResponse, ApiError, PasswordInquiryRequest>({
    mutationFn: (body) => authApi.passwordInquiry(body).then((res) => res.data),
    onSuccess: (data) => {
      // resetToken은 단기 1회용 토큰이라 URL에 남기지 않고 location.state로만 전달
      navigate(RESET_PASSWORD_ROUTE, {
        state: { resetToken: data.resetToken, maskedEmail: data.maskedEmail },
      });
    },
  });

  return {
    inquiryPassword: mutation.mutate,
    isPending: mutation.isPending,
    error: mutation.error,
  };
}
