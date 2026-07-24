import { useMutation } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { authApi } from '../api/authApi';
import type { BusinessLicenseOcrResponse } from '../types';

// 사업자등록증 OCR 자동채움(#587) — 보조 기능이라 실패(400/429/5xx/네트워크)해도 가입 폼
// 자체를 막지 않는다. 호출부(CompanySignupPage)는 onSuccess로 필드를 채우고, onError로는
// (#748) 인라인 실패 피드백만 노출한다 — 콘솔 경고·에러 팝업·폼 제출 차단은 여전히 없다.
// 실패/성공 여부 판정은 CompanySignupPage가 자체 ocrFeedback state로 관리하므로(onSuccess/
// onError 콜백 기반) mutation.isError는 노출하지 않는다 — "무엇이 authoritative 상태인지"를
// 하나로 유지하기 위함(리뷰어 P3). isPending·reset만 노출: reset은 새 파일 선택 시 이전
// mutation 결과(data/error)를 지워 다음 호출이 깨끗한 상태에서 시작하게 한다.
export function useBusinessLicenseOcr() {
  const mutation = useMutation<BusinessLicenseOcrResponse, ApiError, File>({
    mutationFn: (file) => authApi.businessLicenseOcr(file).then((res) => res.data),
  });

  return {
    runOcr: mutation.mutate,
    isPending: mutation.isPending,
    reset: mutation.reset,
  };
}
