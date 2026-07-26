import { useQuery } from '@tanstack/react-query';
import { reportApi } from '../api/reportApi';
import { mockReportListItems } from '../mocks/reportList.mock';
import type { ReportListFilters } from '../types';

// 보고서 목록/이력 관리(#463) — 회사 스코프 전체 보고서 목록.
// 실 백엔드 호출 실패/404 시에도 프론트엔드 테스트를 위해 목 데이터로 폴백한다.
export function useCompanyReports(filters: ReportListFilters) {
  return useQuery({
    queryKey: ['report', 'company-list', filters] as const,
    queryFn: async ({ signal }) => {
      try {
        const res = await reportApi.listCompanyReports(filters, signal);
        if (res.data && Array.isArray(res.data.content)) {
          return res.data;
        }
      } catch (err) {
        console.warn('[useCompanyReports] API 호출 실패 — 하이브리드 목 폴백 사용:', err);
      }
      const page = filters.page ?? 0;
      const size = filters.size ?? 10;
      const filtered = mockReportListItems.filter((item) => {
        if (filters.facilityId && item.facilityId !== filters.facilityId) return false;
        if (filters.status && item.status !== filters.status) return false;
        if (filters.query && !item.facilityName.toLowerCase().includes(filters.query.toLowerCase())) {
          return false;
        }
        return true;
      });
      return {
        content: filtered.slice(page * size, page * size + size),
        page,
        totalElements: filtered.length,
      };
    },
  });
}
