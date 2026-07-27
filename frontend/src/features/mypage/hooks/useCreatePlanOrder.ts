import { useMutation } from '@tanstack/react-query';
import { mypageApi } from '../api/mypageApi';
import type { PlanName, PlanOrder } from '../types';

// 토스페이먼츠 결제창 연동(#989, HAJA-490) 2단계 흐름의 1단계 — 주문 생성.
// (코드 리뷰 P2 픽스 — 결제창 진입 전 서버가 계산한 실 금액을 확인 화면에 보여주기 위해
// requestPayment 호출과 분리했다. 이전엔 주문 생성 직후 곧바로 결제창을 열어 금액을 확인할
// 기회 자체가 없었다.) 성공 시 응답(PlanOrder)을 PlanCard가 상태로 들고 있다가 확인 모달에
// 표시하고, 사용자가 승인하면 useRequestTossPayment로 넘긴다.
export function useCreatePlanOrder() {
  return useMutation<PlanOrder, unknown, PlanName>({
    mutationFn: (planName) => mypageApi.createPlanOrder(planName).then((res) => res.data),
  });
}
