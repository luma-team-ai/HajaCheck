import { useMutation, useQueryClient } from '@tanstack/react-query';
import { mypageApi } from '../api/mypageApi';
import type { MyPlan } from '../types';

type ConfirmPaymentPayload = { paymentKey: string; orderId: string; amount: number };

// 토스페이먼츠 결제 승인(#989, HAJA-490) — /payments/success 라우트가 결제창 successUrl 리다이렉트
// 쿼리(paymentKey/orderId/amount)를 그대로 넘겨 호출한다. 백엔드가 멱등 처리해 동일 orderId로
// 새로고침·중복 진입해도 같은 결과를 200으로 돌려주므로(handoff §3), 호출부는 "중복 결제" 류
// 오인 문구를 띄우지 않고 그냥 성공으로 처리하면 된다. 성공 시 plan/seats 쿼리를 무효화해
// 갱신된 플랜명·가격·다음 결제일·좌석 한도를 마이페이지 복귀 시 즉시 반영한다.
export function useConfirmPayment() {
  const queryClient = useQueryClient();
  return useMutation<MyPlan, unknown, ConfirmPaymentPayload>({
    mutationFn: (payload) => mypageApi.confirmPayment(payload).then((res) => res.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['mypage', 'plan'] });
      queryClient.invalidateQueries({ queryKey: ['mypage', 'seats'] });
    },
  });
}
