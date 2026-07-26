import type { ReportListSummary } from '../types';

type Props = {
  summary: ReportListSummary | undefined;
  isLoading: boolean;
  isError: boolean;
};

const ITEMS: { key: keyof ReportListSummary; label: string }[] = [
  { key: 'totalCount', label: '전체' },
  { key: 'finalizedCount', label: '완료' },
  { key: 'draftCount', label: '편집 중' },
  { key: 'issuedThisMonthCount', label: '이번 달 발급' },
];

// 보고서 목록/이력 관리(#463) KPI 4종 — Figma 시안(4열 + border-l 구분선), 변화율 배지는 없다.
export function ReportListKpiBar({ summary, isLoading }: Props) {
  if (isLoading) {
    return <div className="px-8 py-6 text-sm text-text-muted">통계를 불러오는 중입니다...</div>;
  }

  const displaySummary = summary ?? {
    totalCount: 0,
    finalizedCount: 0,
    draftCount: 0,
    issuedThisMonthCount: 0,
  };

  return (
    <div className="flex border-b border-border px-8 py-6">
      {ITEMS.map((item, index) => {
        const rawValue = displaySummary[item.key];
        const value = typeof rawValue === 'number' && !Number.isNaN(rawValue) ? rawValue : 0;
        return (
          <div
            key={item.key}
            className={`flex-1 flex flex-col gap-1 ${index > 0 ? 'border-l border-border pl-6' : ''}`}
          >
            <span className="text-xs font-semibold uppercase tracking-wide text-text-muted">{item.label}</span>
            <span className="text-5xl font-bold leading-10 text-heading">{value}</span>
          </div>
        );
      })}
    </div>
  );
}
