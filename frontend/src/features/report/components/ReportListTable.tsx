import { useState } from 'react';
import { Link } from 'react-router-dom';
import { ErrorFallback } from '../../../shared/components/ErrorFallback';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import type { TableColumn } from '../../../shared/components/Table/Table';
import { Table } from '../../../shared/components/Table/Table';
import type { ReportListItem } from '../types';
import { ReportGradeBadges } from './ReportGradeBadges';
import { ReportRowMenu } from './ReportRowMenu';
import { ReportStatusBadge } from './ReportStatusBadge';
import { SelectionCheckbox } from './SelectionCheckbox';
import { formatReportListTitle } from '../utils/reportListFormat';

type Props = {
  reports: ReportListItem[] | undefined;
  isLoading: boolean;
  isError: boolean;
  onRetry: () => void;
  selectedIds: Set<number>;
  onSelectionChange: (ids: Set<number>) => void;
  onOpenVersionHistory: (report: ReportListItem) => void;
};

function formatUpdatedAt(iso: string): string {
  const date = new Date(iso);
  const yy = String(date.getFullYear()).slice(2);
  const mm = String(date.getMonth() + 1).padStart(2, '0');
  const dd = String(date.getDate()).padStart(2, '0');
  const hh = String(date.getHours()).padStart(2, '0');
  const min = String(date.getMinutes()).padStart(2, '0');
  return `${yy}.${mm}.${dd} ${hh}:${min}`;
}

// 보고서 목록/이력 관리(#463) 테이블 — InspectionTable(하자 목록 개편)과 동일한 체크박스+shared
// Table 조합 패턴. 행의 "⋮" 메뉴는 한 번에 하나만 열리도록 이 컴포넌트가 openMenuId를 소유한다.
export function ReportListTable({
  reports,
  isLoading,
  isError,
  onRetry,
  selectedIds,
  onSelectionChange,
  onOpenVersionHistory,
}: Props) {
  const [openMenuId, setOpenMenuId] = useState<number | null>(null);

  const rows = reports ?? [];
  const visibleIds = rows.map((row) => row.id);
  const selectedVisibleCount = visibleIds.filter((id) => selectedIds.has(id)).length;
  const isAllSelected = visibleIds.length > 0 && selectedVisibleCount === visibleIds.length;
  const isPartiallySelected = selectedVisibleCount > 0 && !isAllSelected;

  function handleToggleAll() {
    const next = new Set(selectedIds);
    if (visibleIds.every((id) => next.has(id))) {
      visibleIds.forEach((id) => next.delete(id));
    } else {
      visibleIds.forEach((id) => next.add(id));
    }
    onSelectionChange(next);
  }

  function handleToggleRow(id: number) {
    const next = new Set(selectedIds);
    if (next.has(id)) {
      next.delete(id);
    } else {
      next.add(id);
    }
    onSelectionChange(next);
  }

  const columns: TableColumn<ReportListItem>[] = [
    {
      key: 'id',
      header: (
        <span className="flex items-center gap-2">
          <SelectionCheckbox
            ariaLabel="현재 페이지 보고서 전체 선택"
            checked={isAllSelected}
            disabled={visibleIds.length === 0}
            indeterminate={isPartiallySelected}
            onChange={handleToggleAll}
          />
          <span>보고서명</span>
        </span>
      ),
      render: (row) => {
        const title = formatReportListTitle(row.facilityName, row.updatedAt, row.roundNo);
        return (
          <span className="flex items-center gap-2">
            <SelectionCheckbox
              ariaLabel={`${title} 선택`}
              checked={selectedIds.has(row.id)}
              onChange={() => handleToggleRow(row.id)}
            />
            <Link
              to={`/inspections/${row.inspectionId}/reports/generate?reportId=${row.id}`}
              className="font-medium text-zinc-900 no-underline hover:underline"
              onClick={(event) => event.stopPropagation()}
            >
              {title}
            </Link>
          </span>
        );
      },
    },
    { key: 'facilityName', header: '시설물' },
    { key: 'roundNo', header: '회차', render: (row) => <span>{row.roundNo}회차</span> },
    {
      key: 'gradeDistribution',
      header: '하자 요약',
      render: (row) => <ReportGradeBadges distribution={row.gradeDistribution} />,
    },
    { key: 'status', header: '상태', render: (row) => <ReportStatusBadge status={row.status} /> },
    { key: 'version', header: '버전', render: (row) => <span>v{row.version}</span> },
    { key: 'updatedAt', header: '최종 수정', render: (row) => <span>{formatUpdatedAt(row.updatedAt)}</span> },
    {
      key: 'pdfUrl',
      header: '',
      render: (row) => (
        <span className="relative inline-block">
          <button
            type="button"
            aria-label={`${formatReportListTitle(row.facilityName, row.updatedAt, row.roundNo)} 작업 메뉴 열기`}
            className="cursor-pointer rounded-full border-none bg-none px-1.5 py-1 text-zinc-500"
            onClick={(event) => {
              event.stopPropagation();
              setOpenMenuId((current) => (current === row.id ? null : row.id));
            }}
          >
            ⋮
          </button>
          {openMenuId === row.id && (
            <ReportRowMenu
              onOpenHistory={() => onOpenVersionHistory(row)}
              onClose={() => setOpenMenuId(null)}
            />
          )}
        </span>
      ),
    },
  ];

  if (isLoading) {
    return <LoadingSpinner className="flex items-center justify-start gap-2 py-6 px-6" />;
  }

  if (isError) {
    return (
      <div className="px-6 py-6">
        <ErrorFallback message="보고서 목록을 불러오지 못했습니다." onRetry={onRetry} />
      </div>
    );
  }

  return (
    <div className="overflow-x-auto">
      {/* 행 클릭 시 버전 이력 플라이아웃 연동은 PRD 필수 요구사항이다
          (docs/_local/prds/REPORT/NOTES.md §2.2 "[WHEN: 보고서 목록/이력 관리 개발 시] MUST: 행 클릭 시
          버전 이력 플라이아웃"). 체크박스·보고서명 링크·⋮ 메뉴 버튼은 각자 클릭 핸들러에서
          stopPropagation해 이 행 클릭과 충돌하지 않는다. */}
      <Table
        columns={columns}
        data={rows}
        emptyMessage="조건에 맞는 보고서가 없습니다"
        onRowClick={onOpenVersionHistory}
      />
    </div>
  );
}
