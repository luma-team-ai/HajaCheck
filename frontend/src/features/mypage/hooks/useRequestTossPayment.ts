import { ANONYMOUS } from '@tosspayments/tosspayments-sdk';
import { useMutation } from '@tanstack/react-query';
import { PAYMENT_FAIL_ROUTE, PAYMENT_SUCCESS_ROUTE } from '../../../shared/constants/routes';
import { loadTossPaymentsSdk } from '../../../shared/lib/tossPayments/loadTossPaymentsSdk';
import type { PlanOrder } from '../types';

// vite base(예: '/app/')를 반영한 절대 URL로 조립한다 — shared/constants/authPaths.ts의
// normalizePath와 동일한 이유(basename 배포에서도 successUrl/failUrl이 실제 서비스 경로와
// 정확히 일치해야 함). 토스페이먼츠 결제창은 새 origin/탭이 될 수 있어 상대경로가 아닌
// 절대 URL이 필요하다(SDK 계약 — successUrl/failUrl은 "오리진을 포함한 형태"로 요구됨).
function absoluteAppUrl(routePath: string): string {
  const path = `${import.meta.env.BASE_URL}${routePath.replace(/^\//, '')}`.replace(/\/{2,}/g, '/');
  return `${window.location.origin}${path}`;
}

// 토스페이먼츠 결제창 연동(#989, HAJA-490) 2단계 흐름의 2단계 — 사용자가 확인 화면에서 서버가
// 계산한 실 금액(order.amount)을 보고 승인한 뒤에만 결제창을 연다(코드 리뷰 P2 픽스, 위
// useCreatePlanOrder 참고). SDK 로드(키 미설정 시 TossClientKeyMissingError로 즉시 실패,
// "조용한 undefined" 금지) → requestPayment. redirect 방식이라 정상 흐름이면 브라우저가
// 결제창으로 이동(페이지 전환)하므로 이 mutation의 Promise는 통상 resolve되지 않는다 —
// 사용자가 결제창을 취소하거나 SDK 로드가 실패한 경우에만 reject되어 onError로 잡힌다
// (UI 갱신 자체는 성공 시 /payments/success 라우트가 담당 — 여기서 쿼리 무효화하지 않는다).
export function useRequestTossPayment() {
  return useMutation<void, unknown, PlanOrder>({
    mutationFn: async (order) => {
      const tossPayments = await loadTossPaymentsSdk();
      const payment = tossPayments.payment({ customerKey: ANONYMOUS });

      await payment.requestPayment({
        method: 'CARD',
        amount: { currency: 'KRW', value: order.amount },
        orderId: order.orderId,
        orderName: order.orderName,
        successUrl: absoluteAppUrl(PAYMENT_SUCCESS_ROUTE),
        failUrl: absoluteAppUrl(PAYMENT_FAIL_ROUTE),
      });
    },
  });
}
