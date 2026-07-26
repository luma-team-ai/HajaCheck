import { useQuery } from '@tanstack/react-query';
import { reportApi } from '../api/reportApi';
import { mockReportListItems } from '../mocks/reportList.mock';
import type { ReportListSummary } from '../types';
import { hybridFetchFallback } from '../../../shared/utils/hybridFetchFallback';

// 보고서 목록/이력 관리(#463) KPI 4종(전체/완료/편집 중/이번 달 발급).
// 실 API를 우선 사용하고, 회사 요약 엔드포인트가 아직 없는 개발 환경에서만 목으로 폴백한다.
export function useCompanyReportsSummary() {
  return useQuery({
    queryKey: ['report', 'company-summary'] as const,
    queryFn: ({ signal }) => hybridFetchFallback({
      fetcher: () => reportApi.getCompanyReportsSummary(signal).then((res) => {
        const d = (res.data ?? {}) as Partial<ReportListSummary> & Record<string, unknown>;
        if (d && (typeof d.totalCount === 'number' || typeof d.total_count === 'number')) {
          return {
            totalCount:
              typeof d.totalCount === 'number'
                ? d.totalCount
                : Number(d.total_count ?? d.totalCount ?? 0),
            finalizedCount:
              typeof d.finalizedCount === 'number'
                ? d.finalizedCount
                : Number(d.finalized_count ?? d.finalizedCount ?? 0),
            draftCount:
              typeof d.draftCount === 'number'
                ? d.draftCount
                : Number(d.draft_count ?? d.draftCount ?? 0),
            issuedThisMonthCount:
              typeof d.issuedThisMonthCount === 'number'
                ? d.issuedThisMonthCount
                : Number(d.issued_this_month_count ?? d.issuedThisMonthCount ?? 0),
          };
        }
        throw Object.assign(new Error('Invalid report summary response'), { status: 502 });
      }),
      fallback: () => {
      const finalizedCount = mockReportListItems.filter((i) => i.status === 'FINALIZED').length;
      const draftCount = mockReportListItems.filter((i) => i.status === 'DRAFT').length;
      const now = new Date();
      const issuedThisMonthCount = mockReportListItems.filter((i) => {
        if (i.status !== 'FINALIZED') return false;
        const d = new Date(i.updatedAt);
        return d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth();
      }).length;

      return {
        totalCount: mockReportListItems.length,
        finalizedCount,
        draftCount,
        issuedThisMonthCount,
      };
      },
      fallbackOnEmptyArray: false,
    }),
  });
}
