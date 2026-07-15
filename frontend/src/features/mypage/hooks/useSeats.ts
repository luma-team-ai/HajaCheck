import { useQuery } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { mypageApi } from '../api/mypageApi';
import { mockSeats } from '../mocks/mypage.mock';
import type { SeatsInfo } from '../types';
import { fetchWithFallback } from '../utils/fetchWithFallback';

// useMyPlan과 동일한 폴백 규약(HAJA-185) — PLAN_NOT_FOUND는 폴백하지 않고 그대로 에러로 노출.
// 에러 제네릭(ApiError)을 명시해 호출부가 error를 캐스팅 없이 바로 사용할 수 있게 한다.
export function useSeats() {
  return useQuery<SeatsInfo, ApiError>({
    queryKey: ['mypage', 'seats'],
    queryFn: () => fetchWithFallback(() => mypageApi.getSeats().then((res) => res.data), mockSeats),
  });
}
