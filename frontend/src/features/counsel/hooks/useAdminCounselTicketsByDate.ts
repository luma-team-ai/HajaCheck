import { keepPreviousData, useQuery } from '@tanstack/react-query';
import type { ApiError, PageResponse } from '../../../shared/api/types';
import { counselApi } from '../api/counselApi';
import type { CounselTicketSummaryResponse } from '../types';

// 플랫폼 관리자 상담 관리(#1168) — GET /api/counsel/tickets/admin react-query 래핑.
// features/platform-admin/hooks/usePlatformAdminUsers.ts 패턴 준용: queryKey에 date/page/size를
// 모두 포함해 조건이 바뀌면 자동 재조회되고, keepPreviousData로 페이지 전환 시 깜빡임을 없앤다.
export function useAdminCounselTicketsByDate(date: string, page: number, size: number) {
  return useQuery<PageResponse<CounselTicketSummaryResponse>, ApiError>({
    queryKey: ['counsel', 'admin-tickets', date, page, size],
    queryFn: () => counselApi.getAdminTicketsByDate(date, page, size).then((res) => res.data),
    placeholderData: keepPreviousData,
  });
}
