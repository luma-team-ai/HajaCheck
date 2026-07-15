import { useQuery } from '@tanstack/react-query';
import { mypageApi } from '../api/mypageApi';
import { mockSeats } from '../mocks/mypage.mock';
import { fetchWithFallback } from '../utils/fetchWithFallback';

// useMyPlan과 동일한 폴백 규약(HAJA-185) — PLAN_NOT_FOUND는 폴백하지 않고 그대로 에러로 노출
export function useSeats() {
  return useQuery({
    queryKey: ['mypage', 'seats'],
    queryFn: () => fetchWithFallback(() => mypageApi.getSeats().then((res) => res.data), mockSeats),
  });
}
