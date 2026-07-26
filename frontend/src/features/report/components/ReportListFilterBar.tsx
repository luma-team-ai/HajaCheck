import { useEffect, useRef, useState } from 'react';
import { useReportFacilityOptions } from '../hooks/useReportFacilityOptions';
import type { ReportListFilters, ReportListPeriod, ReportListStatus } from '../types';

type Props = {
  filters: ReportListFilters;
  onChange: (filters: ReportListFilters) => void;
};

type Option<T extends string> = { value: T; label: string };

const STATUS_OPTIONS: Option<ReportListStatus>[] = [
  { value: 'DRAFT', label: '편집 중' },
  { value: 'FINALIZED', label: '완료' },
];

const PERIOD_OPTIONS: Option<ReportListPeriod>[] = [
  { value: 'ALL', label: '기간' },
  { value: '1M', label: '최근 1개월' },
  { value: '3M', label: '최근 3개월' },
  { value: '6M', label: '최근 6개월' },
];

function FilterDropdown<T extends string>({
  value,
  label,
  options,
  ariaLabel,
  onChange,
  className = 'w-44',
}: {
  value: T;
  label: string;
  options: Option<T>[];
  ariaLabel: string;
  onChange: (value: T) => void;
  className?: string;
}) {
  const [isOpen, setIsOpen] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const handleOutsideClick = (event: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target as Node)) setIsOpen(false);
    };
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setIsOpen(false);
    };
    document.addEventListener('mousedown', handleOutsideClick);
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('mousedown', handleOutsideClick);
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, []);
  const selectedLabel = options.find((option) => option.value === value)?.label ?? label;

  return (
    <div ref={dropdownRef} className={`relative inline-block text-left ${className}`}>
      <button
        type="button"
        onClick={() => setIsOpen((previous) => !previous)}
        aria-haspopup="listbox"
        aria-expanded={isOpen}
        aria-label={ariaLabel}
        className="inline-flex w-full items-center justify-between gap-2 rounded-full border border-border bg-surface-muted px-3 py-1.5 text-sm font-medium text-heading shadow-none transition-colors hover:bg-surface focus:outline-hidden"
      >
        <span>{selectedLabel}</span>
        <svg
          className={`h-3.5 w-3.5 text-zinc-400 transition-transform duration-150 ${isOpen ? 'rotate-180' : ''}`}
          fill="none"
          viewBox="0 0 24 24"
          stroke="currentColor"
          strokeWidth={2}
          aria-hidden="true"
        >
          <path strokeLinecap="round" strokeLinejoin="round" d="M19 9l-7 7-7-7" />
        </svg>
      </button>
      {isOpen && (
        <div
          role="listbox"
          aria-label={`${ariaLabel} 목록`}
          className="absolute left-0 top-full z-50 mt-1.5 max-h-60 w-full overflow-y-auto rounded-2xl border border-border bg-surface-muted p-1.5 shadow-lg flex flex-col gap-0.5 animate-in fade-in zoom-in-95"
        >
          {options.map((option) => {
            const isSelected = option.value === value;
            return (
              <button
                key={option.value}
                type="button"
                role="option"
                aria-selected={isSelected}
                onClick={() => {
                  onChange(option.value);
                  setIsOpen(false);
                }}
                className={`flex w-full cursor-pointer items-center justify-between rounded-xl px-3 py-2 text-left text-sm font-medium transition-colors ${
                  isSelected
                    ? 'bg-zinc-100 font-semibold text-zinc-900'
                    : 'text-zinc-700 hover:bg-zinc-50 hover:text-zinc-900'
                }`}
              >
                <span>{option.label}</span>
                {isSelected && (
                  <svg className="h-4 w-4 text-zinc-900" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2.5} aria-hidden="true">
                    <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                  </svg>
                )}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}

// 보고서 목록/이력 관리(#463) 검색/필터 바 — 통계 대시보드와 동일한 각진 팝오버 드롭다운 스타일을 사용한다.
export function ReportListFilterBar({ filters, onChange }: Props) {
  const { data: facilityOptions } = useReportFacilityOptions();
  function handleReset() {
    onChange({ page: 0, size: filters.size });
  }

  return (
    <div className="flex flex-wrap items-center gap-2 px-6 py-4">
      <div className="relative flex w-64 items-center">
        <svg className="pointer-events-none absolute left-3.5 h-4 w-4 text-text-muted" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth="2" aria-hidden="true">
          <circle cx="11" cy="11" r="8" />
          <path strokeLinecap="round" strokeLinejoin="round" d="m21 21-4.3-4.3" />
        </svg>
        <input
          type="text"
          value={filters.query ?? ''}
          onChange={(event) => onChange({ ...filters, query: event.target.value || undefined, page: 0 })}
          placeholder="보고서 · 시설물 검색"
          aria-label="보고서 · 시설물 검색"
          className="w-full rounded-full border border-border bg-surface-muted py-1.5 pl-9 pr-4 text-sm text-heading placeholder:text-text-muted"
        />
      </div>

      <FilterDropdown
        value={filters.facilityId == null ? '' : String(filters.facilityId)}
        label="시설물"
        ariaLabel="시설물 필터"
        options={[
          { value: '', label: '시설물' },
          ...(facilityOptions ?? []).map((option) => ({ value: String(option.id), label: option.name })),
        ]}
        className="w-52"
        onChange={(value) => onChange({ ...filters, facilityId: value === '' ? undefined : Number(value), page: 0 })}
      />

      <FilterDropdown
        value={filters.status ?? ''}
        label="상태"
        ariaLabel="상태 필터"
        options={[{ value: '', label: '상태' }, ...STATUS_OPTIONS]}
        onChange={(value) => onChange({ ...filters, status: value === '' ? undefined : value as ReportListStatus, page: 0 })}
      />

      <FilterDropdown
        value={filters.period ?? 'ALL'}
        label="기간"
        ariaLabel="기간 필터"
        options={PERIOD_OPTIONS}
        onChange={(value) => onChange({ ...filters, period: value === 'ALL' ? undefined : value, page: 0 })}
      />

      <button type="button" onClick={handleReset} className="cursor-pointer border-none bg-none pl-2 text-xs text-text-muted underline">
        초기화
      </button>
    </div>
  );
}
