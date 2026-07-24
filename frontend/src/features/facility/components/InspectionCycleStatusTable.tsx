import { useMemo, useRef, useState } from 'react';
import type { KeyboardEvent } from 'react';
import { Pagination } from '../../../shared/components/Pagination/Pagination';
import { INSPECTION_CYCLE_COLOR_CLASS } from '../inspectionCycleColors';
import { useInspectionCycleStatusRows } from '../hooks/useInspectionCycleStatusRows';
import type { InspectionCycleStatusRow } from '../types';
import { deriveInspectionCycleStatus } from '../utils/inspectionCycleStatus';
import { InspectionCycleStatusBadge } from './InspectionCycleStatusBadge';

// 필터 2그룹(#746) — 기간(그룹1)과 긴급도(그룹2)는 서로 다른 축이라 분리한 별도 상태로 관리하고,
// 목록은 두 조건을 AND로 결합해 필터링한다. 기존 '전체/임박/초과' 단일 그룹에서 '전체'는 기간
// 그룹으로, 임박/초과는 긴급도 그룹으로 재구성했다.
type PeriodFilterKey = 'all' | 3 | 6 | 12;
type UrgencyFilterKey = 'upcoming' | 'overdue';

const PERIOD_FILTER_OPTIONS: { key: PeriodFilterKey; label: string }[] = [
  { key: 'all', label: '전체' },
  { key: 3, label: '3개월' },
  { key: 6, label: '6개월' },
  { key: 12, label: '1년' },
];

const URGENCY_FILTER_OPTIONS: { key: UrgencyFilterKey; label: string; dotClass: string }[] = [
  { key: 'upcoming', label: '임박', dotClass: INSPECTION_CYCLE_COLOR_CLASS.upcomingDotBg },
  { key: 'overdue', label: '초과', dotClass: INSPECTION_CYCLE_COLOR_CLASS.overdueDotBg },
];

// 페이지당 행수 — 요구사항 예시값(10)을 그대로 상수화. 별도 페이지 크기 선택 UI는 이번 범위 밖.
const PAGE_SIZE = 10;

const FILTER_PILL_BASE = 'inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 text-sm font-medium';
const FILTER_PILL_ACTIVE = 'bg-primary text-surface';
const FILTER_PILL_INACTIVE = 'border border-border bg-surface text-text-default hover:bg-surface-muted';

const TH_CLASS =
  'text-left text-text-muted font-semibold py-2.5 px-3 bg-[#f6f7f9] border-b border-[#eee] whitespace-nowrap';
const TD_CLASS = 'p-3 border-b border-[#f4f4f4] whitespace-nowrap';

type Props = {
  selectedId: number | null;
  onSelectRow: (row: InspectionCycleStatusRow) => void;
  /** 상태 계산 기준일 — 미지정 시 실제 오늘. 데모 화면은 고정 기준일(INSPECTION_CYCLE_DEMO_TODAY)을 주입한다. */
  today?: Date;
};

// 24개월 등 3버튼(3/6/12)에 없는 주기는 '전체'에서만 노출되도록 정확히 cycleMonths와 일치할 때만 통과시킨다.
function matchesPeriodFilter(row: InspectionCycleStatusRow, period: PeriodFilterKey): boolean {
  if (period === 'all') {
    return true;
  }
  return row.cycleMonths === period;
}

// urgency가 없으면(미선택) 긴급도 전체(임박·초과·여유 등 모두) 통과.
function matchesUrgencyFilter(
  row: InspectionCycleStatusRow,
  urgency: UrgencyFilterKey | null,
  today?: Date,
): boolean {
  if (!urgency) {
    return true;
  }
  const status = deriveInspectionCycleStatus(row.nextInspectionDueAt, today);
  return status.kind === urgency;
}

export function InspectionCycleStatusTable({ selectedId, onSelectRow, today }: Props) {
  const { data, isLoading, isError } = useInspectionCycleStatusRows();
  const [periodFilter, setPeriodFilter] = useState<PeriodFilterKey>('all');
  const [urgencyFilter, setUrgencyFilter] = useState<UrgencyFilterKey | null>(null);
  const [currentPage, setCurrentPage] = useState(1);
  const [focusedIndex, setFocusedIndex] = useState(0);
  const rowRefs = useRef<(HTMLTableRowElement | null)[]>([]);

  const filteredRows = useMemo(
    () =>
      (data ?? []).filter(
        (row) => matchesPeriodFilter(row, periodFilter) && matchesUrgencyFilter(row, urgencyFilter, today),
      ),
    [data, periodFilter, urgencyFilter, today],
  );

  const totalItems = filteredRows.length;
  const totalPages = Math.max(1, Math.ceil(totalItems / PAGE_SIZE));
  // currentPage가 필터 변경 직후 새 결과 범위를 벗어나는 경우를 대비한 방어적 clamp(정상 흐름에서는
  // 아래 핸들러들이 필터·페이지 전환 시 항상 1페이지로 되돌리므로 실제로는 거의 발동하지 않는다).
  const safeCurrentPage = Math.min(currentPage, totalPages);
  const pageRows = useMemo(
    () => filteredRows.slice((safeCurrentPage - 1) * PAGE_SIZE, safeCurrentPage * PAGE_SIZE),
    [filteredRows, safeCurrentPage],
  );

  const rangeStart = totalItems === 0 ? 0 : (safeCurrentPage - 1) * PAGE_SIZE + 1;
  const rangeEnd = Math.min(safeCurrentPage * PAGE_SIZE, totalItems);

  const handlePeriodFilterChange = (key: PeriodFilterKey) => {
    setPeriodFilter(key);
    setCurrentPage(1);
    setFocusedIndex(0);
  };

  // 긴급도는 단일 선택 토글 — 이미 선택된 값을 다시 누르면 해제(긴급도 전체로 복귀).
  const handleUrgencyFilterToggle = (key: UrgencyFilterKey) => {
    setUrgencyFilter((prev) => (prev === key ? null : key));
    setCurrentPage(1);
    setFocusedIndex(0);
  };

  const handlePageChange = (page: number) => {
    setCurrentPage(page);
    setFocusedIndex(0);
  };

  // 키보드 내비게이션(roving tabindex) — RecentInspectionsTable.tsx와 동일 패턴(react-reviewer P2):
  // 행 그룹의 Tab 정지점을 1개로 유지하고 방향키로 이동. 필터·페이지로 행 수가 줄어도 항상 한 행만 tabIndex=0.
  const rowCount = pageRows.length;
  const safeFocusedIndex = Math.min(focusedIndex, rowCount - 1);
  const focusRow = (index: number) => {
    const clamped = Math.max(0, Math.min(index, rowCount - 1));
    setFocusedIndex(clamped);
    rowRefs.current[clamped]?.focus();
  };
  const handleRowKeyDown = (
    event: KeyboardEvent<HTMLTableRowElement>,
    index: number,
    row: InspectionCycleStatusRow,
  ) => {
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        focusRow(index + 1);
        break;
      case 'ArrowUp':
        event.preventDefault();
        focusRow(index - 1);
        break;
      case 'Home':
        event.preventDefault();
        focusRow(0);
        break;
      case 'End':
        event.preventDefault();
        focusRow(rowCount - 1);
        break;
      case 'Enter':
      case ' ':
        event.preventDefault();
        onSelectRow(row);
        break;
      default:
        break;
    }
  };

  return (
    <section className="flex flex-col gap-4 rounded-2xl border border-border bg-surface p-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h2 className="m-0 text-base font-bold text-heading">전체 시설물 점검 주기 현황</h2>
        <div className="flex flex-wrap items-center gap-4">
          <div className="flex gap-2" role="group" aria-label="기간 필터">
            {PERIOD_FILTER_OPTIONS.map((option) => {
              const isActive = option.key === periodFilter;
              return (
                <button
                  key={option.key}
                  type="button"
                  aria-pressed={isActive}
                  onClick={() => handlePeriodFilterChange(option.key)}
                  className={`${FILTER_PILL_BASE} ${isActive ? FILTER_PILL_ACTIVE : FILTER_PILL_INACTIVE}`}
                >
                  {option.label}
                </button>
              );
            })}
          </div>
          <div className="flex gap-2" role="group" aria-label="긴급도 필터">
            {URGENCY_FILTER_OPTIONS.map((option) => {
              const isActive = option.key === urgencyFilter;
              return (
                <button
                  key={option.key}
                  type="button"
                  aria-pressed={isActive}
                  onClick={() => handleUrgencyFilterToggle(option.key)}
                  className={`${FILTER_PILL_BASE} ${isActive ? FILTER_PILL_ACTIVE : FILTER_PILL_INACTIVE}`}
                >
                  <span className={`h-1.5 w-1.5 rounded-full ${option.dotClass}`} aria-hidden="true" />
                  {option.label}
                </button>
              );
            })}
          </div>
        </div>
      </div>

      {isLoading && <p className="text-sm text-text-muted">불러오는 중...</p>}
      {isError && <p className="text-sm text-text-muted">점검 주기 현황을 불러오지 못했습니다.</p>}
      {!isLoading && !isError && filteredRows.length === 0 && (
        <p className="text-sm text-text-muted">조건에 맞는 시설물이 없습니다.</p>
      )}

      {!isLoading && !isError && filteredRows.length > 0 && (
        <>
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
                {pageRows.map((row, index) => {
                  const isSelected = row.id === selectedId;
                  const status = deriveInspectionCycleStatus(row.nextInspectionDueAt, today);
                  const rowBg =
                    status.kind === 'overdue' && !isSelected ? INSPECTION_CYCLE_COLOR_CLASS.overdueRowBg : '';
                  return (
                    <tr
                      key={row.id}
                      ref={(el) => {
                        rowRefs.current[index] = el;
                      }}
                      tabIndex={index === safeFocusedIndex ? 0 : -1}
                      aria-selected={isSelected}
                      onClick={() => {
                        setFocusedIndex(index);
                        onSelectRow(row);
                      }}
                      onKeyDown={(event) => handleRowKeyDown(event, index, row)}
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
                        <InspectionCycleStatusBadge nextInspectionDueAt={row.nextInspectionDueAt} today={today} />
                      </td>
                      <td className={TD_CLASS}>{row.assigneeName}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>

          <div className="flex flex-wrap items-center justify-between gap-4 border-t border-border pt-4">
            <p className="m-0 text-xs text-text-muted">
              {rangeStart}-{rangeEnd} / {totalItems}
            </p>
            <Pagination currentPage={safeCurrentPage} totalPages={totalPages} onPageChange={handlePageChange} />
          </div>
        </>
      )}
    </section>
  );
}
