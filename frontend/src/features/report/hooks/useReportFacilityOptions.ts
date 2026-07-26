import { useQuery } from '@tanstack/react-query';
import { reportApi } from '../api/reportApi';
import { mockReportFacilityOptions } from '../mocks/reportList.mock';
import { hybridFetchFallback } from '../../../shared/utils/hybridFetchFallback';

// 보고서 목록 필터의 시설물 select 옵션 — GET /api/facilities 재사용.
// 실 시설물 API를 우선 사용하고, 개발 DB가 비었거나 API가 없는 경우에만 목으로 폴백한다.
export function useReportFacilityOptions() {
  return useQuery({
    queryKey: ['report', 'facility-options'] as const,
    queryFn: ({ signal }) => hybridFetchFallback({
      fetcher: () => reportApi.listFacilityOptions(signal).then((res) => res.data),
      fallback: mockReportFacilityOptions,
    }),
  });
}
