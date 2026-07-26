import { keepPreviousData, useQuery } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { planQuotaApi } from '../api/planQuotaApi';
import type { PlanQuotaListParams, PlanQuotaListResponse } from '../planQuota.types';

// 플랜·쿼터 사용자 목록 조회 — 검색·페이지가 바뀔 때마다 새 쿼리 키로 재조회한다.
// keepPreviousData: 페이지 이동 시 표가 빈 화면으로 깜빡이지 않고 이전 페이지를 유지한 채 갱신된다.
// 목 폴백은 두지 않는다 — 개발 환경은 MSW(planQuotaApi.handlers)가 응답하고, 그 밖의 실패는
// 화면이 에러 상태로 정직하게 노출해야 한다(useAdminUsers와 동일 전략).
//
// @param enabled 호출부가 실제로 목록이 필요할 때만 조회하게 한다(기본 true) — 예: 플랜 하향 확인
// 모달의 멤버 로스터는 모달이 닫혀 있으면 100건 조회 자체가 불필요하다(#930 재검토 F-11).
export function usePlanQuotaUsers(params: PlanQuotaListParams, enabled = true) {
  return useQuery<PlanQuotaListResponse, ApiError>({
    queryKey: ['admin', 'plan-quota', params],
    queryFn: () => planQuotaApi.getUsers(params).then((res) => res.data),
    placeholderData: keepPreviousData,
    enabled,
  });
}
