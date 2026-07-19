import { keepPreviousData, useQuery } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { adminApi } from '../api/adminApi';
import type { AdminUserListParams, AdminUserListResponse } from '../types';

// 사용자 목록 조회 — 검색·필터·페이지가 바뀔 때마다 새 쿼리 키로 재조회한다.
// keepPreviousData: 페이지 이동 시 표가 빈 화면으로 깜빡이지 않고 이전 페이지를 유지한 채 갱신된다.
// 목 폴백은 두지 않는다 — 개발 환경은 MSW(adminApi.handlers)가 응답하고, 그 밖의 실패는
// 화면이 에러 상태로 정직하게 노출해야 한다(예제 데이터가 실 API 장애를 가리지 않도록).
export function useAdminUsers(params: AdminUserListParams) {
  return useQuery<AdminUserListResponse, ApiError>({
    queryKey: ['admin', 'users', params],
    queryFn: () => adminApi.getUsers(params).then((res) => res.data),
    placeholderData: keepPreviousData,
  });
}
