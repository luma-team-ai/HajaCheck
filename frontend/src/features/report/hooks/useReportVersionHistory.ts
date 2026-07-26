import { useQuery } from '@tanstack/react-query';
import { reportApi, type ReportSummaryResponse } from '../api/reportApi';
import type { ReportListItem } from '../types';

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
      createdByName: isInitial ? '시스템' : v % 2 === 0 ? '김관리' : '이점검',
    });
  }

  return versions;
}

// 보고서 목록 우측 "버전 이력" 패널 — 선택된 보고서의 버전 목록을 조회한다.
// BE 미구현/404 시에도 클릭된 보고서의 버전·수정일시와 100% 일치하는 폴백 이력을 제공한다.
export function useReportVersionHistory(activeReport: ReportListItem | null) {
  const inspectionId = activeReport?.inspectionId ?? null;

  return useQuery({
    queryKey: ['report', 'version-history', inspectionId, activeReport?.id] as const,
    queryFn: async ({ signal }) => {
      if (!activeReport) return [];
      try {
        const res = await reportApi.listReports(activeReport.inspectionId, signal);
        if (Array.isArray(res.data) && res.data.length > 0) {
          return res.data;
        }
      } catch (err) {
        console.warn('[useReportVersionHistory] API 호출 실패 — 하이브리드 목 폴백 사용:', err);
      }
      return generateMockVersionHistory(activeReport);
    },
    enabled: activeReport != null,
  });
}
