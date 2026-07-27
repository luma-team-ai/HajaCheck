import { useQuery } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { mypageApi } from '../api/mypageApi';
import { mockPayments } from '../mocks/mypage.mock';
import type { PaymentHistoryItem } from '../types';
import { fetchWithFallback } from '../utils/fetchWithFallback';

// 결제 내역 실연동(#864, 토스페이먼츠 #989/HAJA-490) — BillingHistoryModal이 사용한다.
// useMyPlan/useSeats와 동일한 폴백 규약: 백엔드 미기동(NETWORK_ERROR)일 때만 예제 데이터로
// 폴백하고, 그 외 도메인 에러는 그대로 노출한다. enabled=false(기본 true)면 조회하지 않는다 —
// BillingHistoryModal이 열려 있을 때만 조회해 불필요한 선조회를 막는다.
export function usePayments(enabled: boolean = true) {
  return useQuery<PaymentHistoryItem[], ApiError>({
    queryKey: ['mypage', 'payments'],
    queryFn: () =>
      fetchWithFallback(() => mypageApi.getPayments().then((res) => res.data.payments), mockPayments),
    enabled,
  });
}
