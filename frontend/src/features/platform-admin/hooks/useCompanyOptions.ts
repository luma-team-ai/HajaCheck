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
    // 실패해도 자동 재시도로 조용히 흘려보내지 않는다 — 호출부(CreateUserModal)가 isError를
    // 명시적으로 노출해야 "승인된 기업이 0곳"과 "목록 조회 실패"를 사용자가 구별할 수 있다
    // (PR #656 PR머신 리뷰 P3 — 무음 실패 시 개인 계정으로 오등록될 위험).
    retry: 1,
  });
}
