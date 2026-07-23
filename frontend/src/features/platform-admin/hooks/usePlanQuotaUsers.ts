import { keepPreviousData, useQuery } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { planQuotaApi } from '../api/planQuotaApi';
import type { PlanQuotaListParams, PlanQuotaListResponse } from '../planQuota.types';

// 플랜·쿼터 사용자 목록 조회 — 검색·페이지가 바뀔 때마다 새 쿼리 키로 재조회한다.
// keepPreviousData: 페이지 이동 시 표가 빈 화면으로 깜빡이지 않고 이전 페이지를 유지한 채 갱신된다.
// 목 폴백은 두지 않는다 — 개발 환경은 MSW(planQuotaApi.handlers)가 응답하고, 그 밖의 실패는
// 화면이 에러 상태로 정직하게 노출해야 한다(usePlatformAdminUsers와 동일 전략).
export function usePlanQuotaUsers(params: PlanQuotaListParams) {
  return useQuery<PlanQuotaListResponse, ApiError>({
    queryKey: ['platform-admin', 'plan-quota', params],
    queryFn: () => planQuotaApi.getUsers(params).then((res) => res.data),
    placeholderData: keepPreviousData,
  });
}
