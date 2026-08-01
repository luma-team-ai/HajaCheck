import { useQuery } from '@tanstack/react-query';
import { reportApi, type ReportSummaryResponse } from '../api/reportApi';
import type { ReportListItem } from '../types';
import { hybridFetchFallback } from '../../../shared/utils/hybridFetchFallback';

export function generateMockVersionHistory(report: ReportListItem): ReportSummaryResponse[] {
  const versions: ReportSummaryResponse[] = [];
  const baseDate = new Date(report.updatedAt);
  const isValidDate = !Number.isNaN(baseDate.getTime());

  for (let v = report.version; v >= 1; v--) {
    const offsetDays = report.version - v;
    const date = isValidDate
      ? new Date(baseDate.getTime() - offsetDays * 24 * 60 * 60 * 1000)
      : new Date();
    const isCurrent = v === report.version;
    const isInitial = v === 1;

    versions.push({
      id: report.id * 10 + v,
      inspectionId: report.inspectionId,
      version: v,
      status: isCurrent ? report.status : isInitial ? 'DRAFT' : 'FINALIZED',
      groundingCheckPassed: true,
      createdAt: isValidDate ? date.toISOString() : report.updatedAt,
      createdByName: '개발용 이력',
    });
  }

  return versions;
}

// 보고서 목록 우측 "버전 이력" 패널 — 선택된 보고서의 버전 목록을 조회한다.
// 실 버전 API를 우선 사용하고, 개발 환경에서 API가 없거나 빈 경우에만 폴백 이력을 제공한다.
export function useReportVersionHistory(activeReport: ReportListItem | null) {
  const inspectionId = activeReport?.inspectionId ?? null;

  return useQuery({
    queryKey: ['report', 'version-history', inspectionId, activeReport?.id] as const,
    queryFn: ({ signal }) => {
      if (!activeReport) return Promise.resolve([]);
      return hybridFetchFallback({
        fetcher: () => reportApi.listReports(activeReport.inspectionId, signal).then((res) => res.data),
        fallback: () => generateMockVersionHistory(activeReport),
      });
    },
    enabled: activeReport != null,
  });
}
