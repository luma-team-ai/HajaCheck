import { useMutation } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { authApi } from '../api/authApi';
import type { BusinessLicenseOcrResponse } from '../types';

// 사업자등록증 OCR 자동채움(#587) — 보조 기능이라 실패(400/429/5xx/네트워크)해도 가입 폼
// 자체를 막지 않는다. 호출부(CompanySignupPage)가 onSuccess에서만 필드를 채우고, 실패 시엔
// onError를 등록하지 않아 콘솔 경고·에러 팝업 없이 조용히 폴백(수동 입력 유지)한다.
// isPending만 노출해 업로드 컴포넌트가 로딩 문구를 보여줄 수 있게 한다.
export function useBusinessLicenseOcr() {
  const mutation = useMutation<BusinessLicenseOcrResponse, ApiError, File>({
    mutationFn: (file) => authApi.businessLicenseOcr(file).then((res) => res.data),
  });

  return {
    runOcr: mutation.mutate,
    isPending: mutation.isPending,
  };
}
