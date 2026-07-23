import { keepPreviousData, useQuery } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { platformAdminUserApi } from '../api/platformAdminUserApi';
import type { AdminUserListParams, AdminUserListResponse } from '../types';

// features/admin/hooks/useAdminUsers.ts(#405)를 그대로 옮긴 것(#577) — 조회 대상 엔드포인트만
// 회사 스코프 없는 platformAdminUserApi로 바뀐다.
export function usePlatformAdminUsers(params: AdminUserListParams) {
  return useQuery<AdminUserListResponse, ApiError>({
    queryKey: ['platform-admin', 'users', params],
    queryFn: () => platformAdminUserApi.getUsers(params).then((res) => res.data),
    placeholderData: keepPreviousData,
  });
}
