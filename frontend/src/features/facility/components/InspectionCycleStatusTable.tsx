import { useMemo, useState } from 'react';
import { INSPECTION_CYCLE_COLOR_CLASS } from '../inspectionCycleColors';
import { useInspectionCycleStatusRows } from '../hooks/useInspectionCycleStatusRows';
import type { InspectionCycleStatusRow } from '../types';
import { deriveInspectionCycleStatus } from '../utils/inspectionCycleStatus';
import { InspectionCycleStatusBadge } from './InspectionCycleStatusBadge';

type FilterKey = 'all' | 'upcoming' | 'overdue';

const FILTER_OPTIONS: { key: FilterKey; label: string; dotClass?: string }[] = [
  { key: 'all', label: '전체' },
  { key: 'upcoming', label: '임박', dotClass: INSPECTION_CYCLE_COLOR_CLASS.upcomingDotBg },
  { key: 'overdue', label: '초과', dotClass: INSPECTION_CYCLE_COLOR_CLASS.overdueDotBg },
];

const TH_CLASS =
  'text-left text-text-muted font-semibold py-2.5 px-3 bg-[#f6f7f9] border-b border-[#eee] whitespace-nowrap';
const TD_CLASS = 'p-3 border-b border-[#f4f4f4] whitespace-nowrap';

type Props = {
  selectedId: number | null;
  onSelectRow: (row: InspectionCycleStatusRow) => void;
};

function matchesFilter(row: InspectionCycleStatusRow, filter: FilterKey): boolean {
  if (filter === 'all') {
    return true;
  }
  const status = deriveInspectionCycleStatus(row.nextInspectionDueAt);
  return status.kind === filter;
}

export function InspectionCycleStatusTable({ selectedId, onSelectRow }: Props) {
  const { data, isLoading, isError } = useInspectionCycleStatusRows();
  const [filter, setFilter] = useState<FilterKey>('all');

  const filteredRows = useMemo(
    () => (data ?? []).filter((row) => matchesFilter(row, filter)),
    [data, filter],
  );

  return (
    <section className="flex flex-col gap-4 rounded-2xl border border-border bg-surface p-6">
      <div className="flex items-center justify-between">
        <h2 className="m-0 text-base font-bold text-heading">전체 시설물 점검 주기 현황</h2>
        <div className="flex gap-2" role="group" aria-label="상태 필터">
          {FILTER_OPTIONS.map((option) => {
            const isActive = option.key === filter;
            return (
              <button
                key={option.key}
                type="button"
                aria-pressed={isActive}
                onClick={() => setFilter(option.key)}
                className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 text-sm font-medium ${
                  isActive
                    ? 'bg-primary text-surface'
                    : 'border border-border bg-surface text-text-default hover:bg-surface-muted'
                }`}
              >
                {option.dotClass && (
                  <span className={`h-1.5 w-1.5 rounded-full ${option.dotClass}`} aria-hidden="true" />
                )}
                {option.label}
              </button>
            );
          })}
        </div>
      </div>

      {isLoading && <p className="text-sm text-text-muted">불러오는 중...</p>}
      {isError && <p className="text-sm text-text-muted">점검 주기 현황을 불러오지 못했습니다.</p>}
      {!isLoading && !isError && filteredRows.length === 0 && (
        <p className="text-sm text-text-muted">해당 상태의 시설물이 없습니다.</p>
      )}

      {!isLoading && !isError && filteredRows.length > 0 && (
        <div className="overflow-x-auto">
          <table aria-label="전체 시설물 점검 주기 현황" className="w-full border-collapse text-[13px]">
            <thead>
              <tr>
                <th className={`${TH_CLASS} rounded-tl-lg rounded-bl-lg`}>시설물</th>
                <th className={TH_CLASS}>유형</th>
                <th className={TH_CLASS}>주기</th>
                <th className={TH_CLASS}>최근 점검</th>
                <th className={TH_CLASS}>다음 점검</th>
                <th className={TH_CLASS}>상태</th>
                <th className={`${TH_CLASS} rounded-tr-lg rounded-br-lg`}>담당자</th>
              </tr>
            </thead>
            <tbody>
              {filteredRows.map((row) => {
                const isSelected = row.id === selectedId;
                const status = deriveInspectionCycleStatus(row.nextInspectionDueAt);
                const rowBg =
                  status.kind === 'overdue' && !isSelected ? INSPECTION_CYCLE_COLOR_CLASS.overdueRowBg : '';
                return (
                  <tr
                    key={row.id}
                    tabIndex={0}
                    aria-selected={isSelected}
                    onClick={() => onSelectRow(row)}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter' || event.key === ' ') {
                        event.preventDefault();
                        onSelectRow(row);
                      }
                    }}
                    className={`cursor-pointer transition-colors ${INSPECTION_CYCLE_COLOR_CLASS.rowFocusOutline} ${
                      isSelected
                        ? INSPECTION_CYCLE_COLOR_CLASS.rowSelectedBg
                        : `${rowBg} ${INSPECTION_CYCLE_COLOR_CLASS.rowHoverBg}`
                    }`}
                  >
                    <td
                      className={`${TD_CLASS} font-medium text-heading${
                        isSelected ? ` ${INSPECTION_CYCLE_COLOR_CLASS.rowSelectedBar}` : ''
                      }`}
                    >
                      {row.name}
                    </td>
                    <td className={TD_CLASS}>{row.type}</td>
                    <td className={TD_CLASS}>{row.cycleMonths}개월</td>
                    <td className={TD_CLASS}>{row.lastInspectedAt}</td>
                    <td className={TD_CLASS}>{row.nextInspectionDueAt}</td>
                    <td className={TD_CLASS}>
                      <InspectionCycleStatusBadge nextInspectionDueAt={row.nextInspectionDueAt} />
                    </td>
                    <td className={TD_CLASS}>{row.assigneeName}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
