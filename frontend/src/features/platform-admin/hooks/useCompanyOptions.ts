import { useQuery } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { platformAdminCompanyApi } from '../api/platformAdminCompanyApi';
import type { CompanyOption } from '../types';

// 사용자 등록 모달의 기업명 selectbox 데이터 소스(#576) — 승인된 기업 목록은 자주 바뀌지 않으므로
// staleTime을 넉넉히 둔다.
const STALE_TIME_MS = 5 * 60 * 1000;

export function useCompanyOptions() {
  return useQuery<CompanyOption[], ApiError>({
    queryKey: ['platform-admin', 'companies'],
    queryFn: () => platformAdminCompanyApi.getCompanies().then((res) => res.data),
    staleTime: STALE_TIME_MS,
  });
}
