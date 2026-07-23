import { useQuery } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { mypageApi } from '../api/mypageApi';
import { mockMyInspectionsSummary } from '../mocks/myInspections.mock';
import type { MyInspectionsSummary } from '../types';
import { fetchWithFallback } from '../utils/fetchWithFallback';

// 내 점검 이력/보고서 — KPI 4종 (HAJA-366, #668). useMyPlan과 동일한 폴백 규약:
// 백엔드 미배포/네트워크 오류(NETWORK_ERROR)일 때만 예제 데이터로 폴백한다.
export function useMyInspectionsSummary() {
  return useQuery<MyInspectionsSummary, ApiError>({
    queryKey: ['mypage', 'inspections', 'summary'],
    queryFn: () =>
      fetchWithFallback(
        () => mypageApi.getInspectionsSummary().then((res) => res.data),
        mockMyInspectionsSummary,
      ),
  });
}
