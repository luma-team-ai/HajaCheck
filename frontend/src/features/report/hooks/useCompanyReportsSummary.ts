import { useQuery } from '@tanstack/react-query';
import { reportApi } from '../api/reportApi';
import { mockReportListItems } from '../mocks/reportList.mock';
import type { ReportListSummary } from '../types';

// 보고서 목록/이력 관리(#463) KPI 4종(전체/완료/편집 중/이번 달 발급).
// BE 미구현 상태나 404/네트워크 에러 발생 시에도 목 데이터 기반 집계 수치를 폴백 제공한다.
export function useCompanyReportsSummary() {
  return useQuery({
    queryKey: ['report', 'company-summary'] as const,
    queryFn: async ({ signal }) => {
      try {
        const res = await reportApi.getCompanyReportsSummary(signal);
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
      } catch (err) {
        console.warn('[useCompanyReportsSummary] API 호출 실패 — 하이브리드 목 폴백 사용:', err);
      }

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
  });
}
