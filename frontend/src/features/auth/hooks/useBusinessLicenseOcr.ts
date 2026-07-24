import { useMutation } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { authApi } from '../api/authApi';
import type { BusinessLicenseOcrResponse } from '../types';

// 사업자등록증 OCR 자동채움(#587) — 보조 기능이라 실패(400/429/5xx/네트워크)해도 가입 폼
// 자체를 막지 않는다. 호출부(CompanySignupPage)는 onSuccess로 필드를 채우고, onError로는
// (#748) 인라인 실패 피드백만 노출한다 — 콘솔 경고·에러 팝업·폼 제출 차단은 여전히 없다.
// isPending·isError·reset을 노출해 업로드 컴포넌트가 로딩/결과 피드백을 그릴 수 있게 한다.
export function useBusinessLicenseOcr() {
  const mutation = useMutation<BusinessLicenseOcrResponse, ApiError, File>({
    mutationFn: (file) => authApi.businessLicenseOcr(file).then((res) => res.data),
  });

  return {
    runOcr: mutation.mutate,
    isPending: mutation.isPending,
    isError: mutation.isError,
    reset: mutation.reset,
  };
}
