import { useQuery } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { mypageApi } from '../api/mypageApi';
import { mockMyPlan } from '../mocks/mypage.mock';
import type { MyPlan } from '../types';
import { fetchWithFallback } from '../utils/fetchWithFallback';

// 백엔드(#211) 미배포 시 예제 데이터 폴백(HAJA-185) — PLAN_NOT_FOUND(활성 구독 없음)는 폴백하지 않고
// 그대로 에러로 노출해 MyPlanPage가 error.code 기반으로 "구독 없음" 상태를 렌더링하게 한다.
// 에러 제네릭(ApiError)을 명시해 호출부가 error를 캐스팅 없이 바로 사용할 수 있게 한다.
export function useMyPlan() {
  return useQuery<MyPlan, ApiError>({
    queryKey: ['mypage', 'plan'],
    queryFn: () => fetchWithFallback(() => mypageApi.getPlan().then((res) => res.data), mockMyPlan),
  });
}
