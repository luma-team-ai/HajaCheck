import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { mypageApi } from '../api/mypageApi';
import type { MyPlan, PlanName } from '../types';

// 모의 결제(PG 실결제 대체, #712 Figma 리디자인) — 기존 useUpgradeInquiry(업그레이드 문의)를 대체한다.
// 성공 시 plan/seats 쿼리를 무효화해 갱신된 플랜명·가격·다음 결제일·좌석 한도를 즉시 반영한다.
// 403(PLAN_FORBIDDEN, 비소유자)/400(INVALID_INPUT)/409(PLAN_ACTIVE_SUBSCRIPTION_CONFLICT, 동시경합)는
// 호출부(PlanCard)가 error.code로 분기해 안내 문구를 보여준다. 예제 데이터 폴백은 적용하지 않는다 —
// 결제/상태 전이 요청은 실패를 조용히 가리면 안 되는 액션이라 그대로 에러로 노출한다.
// 에러 제네릭(ApiError)을 명시해 호출부가 error를 캐스팅 없이 바로 사용할 수 있게 한다.
export function useCheckout() {
  const queryClient = useQueryClient();
  return useMutation<MyPlan, ApiError, PlanName>({
    mutationFn: (planName) => mypageApi.checkout(planName).then((res) => res.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['mypage', 'plan'] });
      queryClient.invalidateQueries({ queryKey: ['mypage', 'seats'] });
    },
  });
}
