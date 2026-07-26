import type { ChangeEvent } from 'react';
import { useReportFacilityOptions } from '../hooks/useReportFacilityOptions';
import type { ReportListFilters, ReportListPeriod, ReportListStatus } from '../types';

type Props = {
  filters: ReportListFilters;
  onChange: (filters: ReportListFilters) => void;
};

const STATUS_OPTIONS: { value: ReportListStatus; label: string }[] = [
  { value: 'DRAFT', label: '편집 중' },
  { value: 'FINALIZED', label: '완료' },
];

const PERIOD_OPTIONS: { value: ReportListPeriod; label: string }[] = [
  { value: '1M', label: '최근 1개월' },
  { value: '3M', label: '최근 3개월' },
  { value: '6M', label: '최근 6개월' },
  { value: 'ALL', label: '기간' },
];

// 보고서 목록/이력 관리(#463) 검색/필터 바 — 시설물(GET /api/facilities), 상태(DRAFT/FINALIZED),
// 기간, 자유 텍스트 검색(보고서명·시설물명).
export function ReportListFilterBar({ filters, onChange }: Props) {
  const { data: facilityOptions } = useReportFacilityOptions();

  function handleQueryChange(event: ChangeEvent<HTMLInputElement>) {
    onChange({ ...filters, query: event.target.value || undefined, page: 0 });
  }

  function handleFacilityChange(event: ChangeEvent<HTMLSelectElement>) {
    const value = event.target.value;
    onChange({ ...filters, facilityId: value === '' ? undefined : Number(value), page: 0 });
  }

  function handleStatusChange(event: ChangeEvent<HTMLSelectElement>) {
    const value = event.target.value as ReportListStatus | '';
    onChange({ ...filters, status: value === '' ? undefined : value, page: 0 });
  }

  function handlePeriodChange(event: ChangeEvent<HTMLSelectElement>) {
    const value = event.target.value as ReportListPeriod;
    onChange({ ...filters, period: value === 'ALL' ? undefined : value, page: 0 });
  }

  function handleReset() {
    onChange({ page: 0, size: filters.size });
  }

  return (
    <div className="flex flex-wrap items-center gap-2 px-6 py-4">
      <div className="relative flex items-center w-64">
        <svg
          className="pointer-events-none absolute left-3.5 h-4 w-4 text-text-muted"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
          strokeWidth="2"
          aria-hidden="true"
        >
          <circle cx="11" cy="11" r="8" />
          <path strokeLinecap="round" strokeLinejoin="round" d="m21 21-4.3-4.3" />
        </svg>
        <input
          type="text"
          value={filters.query ?? ''}
          onChange={handleQueryChange}
          placeholder="보고서 · 시설물 검색"
          aria-label="보고서 · 시설물 검색"
          className="w-full rounded-full border border-border bg-surface-muted py-1.5 pl-9 pr-4 text-sm text-heading placeholder:text-text-muted"
        />
      </div>

      <select
        value={filters.facilityId ?? ''}
        onChange={handleFacilityChange}
        className="rounded-full border border-border bg-surface-muted px-3 py-1.5 text-sm font-medium text-heading"
        aria-label="시설물 필터"
      >
        <option value="">시설물</option>
        {facilityOptions?.map((option) => (
          <option key={option.id} value={option.id}>
            {option.name}
          </option>
        ))}
      </select>

      <select
        value={filters.status ?? ''}
        onChange={handleStatusChange}
        className="rounded-full border border-border bg-surface-muted px-3 py-1.5 text-sm font-medium text-heading"
        aria-label="상태 필터"
      >
        <option value="">상태</option>
        {STATUS_OPTIONS.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>

      <select
        value={filters.period ?? 'ALL'}
        onChange={handlePeriodChange}
        className="rounded-full border border-border bg-surface-muted px-3 py-1.5 text-sm font-medium text-heading"
        aria-label="기간 필터"
      >
        {PERIOD_OPTIONS.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>

      <button
        type="button"
        onClick={handleReset}
        className="cursor-pointer border-none bg-none pl-2 text-xs text-text-muted underline"
      >
        초기화
      </button>
    </div>
  );
}
