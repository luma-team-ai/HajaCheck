import { useMutation } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import type { ApiError } from '../../../shared/api/types';
import { authApi } from '../api/authApi';
import { COMPANY_SIGNUP_PENDING_ROUTE } from '../constants';
import type { CompanySignupRequest, CompanySignupResponse } from '../types';
import { saveCompanySignupSession } from '../utils/companySignupSession';

export function useCompanySignup() {
  const navigate = useNavigate();

  const mutation = useMutation<CompanySignupResponse, ApiError, CompanySignupRequest>({
    mutationFn: (body) => authApi.signupCompany(body).then((res) => res.data),
    onSuccess: (data, variables) => {
      // opaque signupToken을 쿼리스트링(?token=)으로 넘기면 URL/히스토리/Referer로 유출될 수 있어
      // sessionStorage로 전달 — 새로고침해도 승인 대기 화면 복원 가능, 탭 종료 시 자동 소거(PR머신 P3)
      // companyName은 응답에 없어 요청값을 사용
      saveCompanySignupSession({
        signupToken: data.signupToken,
        companyName: variables.companyName,
        maskedEmail: data.maskedEmail,
      });
      navigate(COMPANY_SIGNUP_PENDING_ROUTE, { replace: true });
    },
  });

  return {
    signup: mutation.mutate,
    isPending: mutation.isPending,
    error: mutation.error,
  };
}
