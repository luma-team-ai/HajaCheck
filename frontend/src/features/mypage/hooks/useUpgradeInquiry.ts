import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { mypageApi } from '../api/mypageApi';
import type { UpgradeInquiryResult } from '../types';

// 업그레이드 문의(PG 실결제 대체, contract.md) — 성공 시 plan 쿼리를 무효화해
// status=UPGRADE_REQUESTED 반영(버튼 상태 변경). 403 PLAN_FORBIDDEN(소유자 아님)은
// 호출부(PlanCard)가 error.code로 분기해 안내 문구를 보여준다. 예제 데이터 폴백은 적용하지 않는다 —
// 상태 전이 요청은 실패를 조용히 가리면 안 되는 액션이라 그대로 에러로 노출한다.
// 에러 제네릭(ApiError)을 명시해 호출부가 error를 캐스팅 없이 바로 사용할 수 있게 한다.
export function useUpgradeInquiry() {
  const queryClient = useQueryClient();
  return useMutation<UpgradeInquiryResult, ApiError>({
    mutationFn: () => mypageApi.requestUpgrade().then((res) => res.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['mypage', 'plan'] });
    },
  });
}
