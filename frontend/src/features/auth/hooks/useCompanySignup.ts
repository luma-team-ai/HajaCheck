import { useMutation } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import type { ApiError } from '../../../shared/api/types';
import { authApi } from '../api/authApi';
import { COMPANY_SIGNUP_PENDING_ROUTE } from '../constants';
import type { CompanySignupRequest, CompanySignupResponse } from '../types';

export function useCompanySignup() {
  const navigate = useNavigate();

  const mutation = useMutation<CompanySignupResponse, ApiError, CompanySignupRequest>({
    mutationFn: (body) => authApi.signupCompany(body).then((res) => res.data),
    onSuccess: (data, variables) => {
      // 승인 대기 화면은 새로고침에도 상태조회가 가능하도록 signupToken을 쿼리스트링으로 전달
      // (companyName은 응답에 없어 요청값을 사용 — 표시정보는 location.state로 함께 전달,
      //  새로고침 시 유실될 수 있는 부가정보이므로 상태조회 API 응답으로 재보강한다)
      navigate(`${COMPANY_SIGNUP_PENDING_ROUTE}?token=${encodeURIComponent(data.signupToken)}`, {
        state: { companyName: variables.companyName, maskedEmail: data.maskedEmail },
      });
    },
  });

  return {
    signup: mutation.mutate,
    isPending: mutation.isPending,
    error: mutation.error,
  };
}
