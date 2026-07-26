import { useQuery } from '@tanstack/react-query';
import { reportApi } from '../api/reportApi';
import { mockReportListItems } from '../mocks/reportList.mock';
import type { ReportListFilters } from '../types';
import { filterReportListItems } from '../utils/reportListFormat';
import { hybridFetchFallback } from '../../../shared/utils/hybridFetchFallback';

// 보고서 목록/이력 관리(#463) — 회사 스코프 전체 보고서 목록.
// 실 API를 우선 사용하고, 회사 목록 엔드포인트가 아직 없는 개발 환경에서만 목으로 폴백한다.
export function useCompanyReports(filters: ReportListFilters) {
  return useQuery({
    queryKey: ['report', 'company-list', filters] as const,
    queryFn: ({ signal }) => hybridFetchFallback({
      fetcher: () => reportApi.listCompanyReports(filters, signal).then((res) => res.data),
      fallback: () => {
      const page = filters.page ?? 0;
      const size = filters.size ?? 10;
      const filtered = filterReportListItems(mockReportListItems, filters);
      return {
        content: filtered.slice(page * size, page * size + size),
        page,
        totalElements: filtered.length,
      };
      },
    }),
  });
}
