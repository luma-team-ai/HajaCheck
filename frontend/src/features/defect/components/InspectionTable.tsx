import { useMemo } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ErrorFallback } from '../../../shared/components/ErrorFallback';
import type { TableColumn } from '../../../shared/components/Table';
import { Table } from '../../../shared/components/Table';
import { GRADE_CLASSES } from './DefectTable';
import { SelectionCheckbox } from './SelectionCheckbox';
import { INSPECTION_STATUS_LABEL } from '../types';
import type { DefectGrade, InspectionListItem } from '../types';

type Props = {
  inspections: InspectionListItem[] | undefined;
  isLoading: boolean;
  isError: boolean;
  onRetry: () => void;
  selectedIds: Set<number>;
  onSelectionChange: (ids: Set<number>) => void;
};

interface InspectionTableRow {
  id: number;
  inspectionCode: string;
  facilityName: string;
  inspectionDate: string;
  roundNo: number;
  defectCount: number;
  gradeDistribution: InspectionListItem['gradeDistribution'];
  status: InspectionListItem['status'];
  assigneeName: string | null;
}

const GRADE_ORDER: DefectGrade[] = ['A', 'B', 'C', 'D', 'E'];

// 하자 목록(DefectTable)의 'DEF-0001' 코드 표기와 대응되는 점검 코드 표기 — 별도 유틸로 승격할
// 만큼 재사용처가 많지 않아 이 컴포넌트에 로컬로 둔다.
function formatInspectionCode(id: number): string {
  return `INS-${String(id).padStart(4, '0')}`;
}

function toTableRow(inspection: InspectionListItem): InspectionTableRow {
  return {
    id: inspection.id,
    inspectionCode: formatInspectionCode(inspection.id),
    facilityName: inspection.facilityName,
    inspectionDate: inspection.inspectionDate,
    roundNo: inspection.roundNo,
    defectCount: inspection.defectCount,
    gradeDistribution: inspection.gradeDistribution,
    status: inspection.status,
    assigneeName: inspection.assigneeName,
  };
}

function createColumns({
  isAllSelected,
  isPartiallySelected,
  selectedIds,
  hasRows,
  onToggleAll,
  onToggleRow,
}: {
  isAllSelected: boolean;
  isPartiallySelected: boolean;
  selectedIds: Set<number>;
  hasRows: boolean;
  onToggleAll: () => void;
  onToggleRow: (id: number) => void;
}): TableColumn<InspectionTableRow>[] {
  return [
    {
      key: 'inspectionCode',
      header: (
        <SelectionCheckbox
          ariaLabel="현재 페이지 점검 전체 선택"
          checked={isAllSelected}
          disabled={!hasRows}
          indeterminate={isPartiallySelected}
          onChange={onToggleAll}
        />
      ),
      render: (row) => (
        <span className="inspection-table__id-cell">
          <SelectionCheckbox
            ariaLabel={`${row.inspectionCode} 선택`}
            checked={selectedIds.has(row.id)}
            onChange={() => onToggleRow(row.id)}
          />
          <Link
            aria-label="점검 상세보기"
            className="defect-list-table__id"
            to={`/inspections/${row.id}/defects`}
          >
            {row.inspectionCode}
          </Link>
        </span>
      ),
    },
    { key: 'facilityName', header: '시설물' },
    { key: 'inspectionDate', header: '점검일' },
    {
      key: 'roundNo',
      header: '회차',
      render: (row) => <span>{row.roundNo}회차</span>,
    },
    {
      key: 'defectCount',
      header: '하자 건수',
      render: (row) => <span>{row.defectCount}건</span>,
    },
    {
      key: 'gradeDistribution',
      header: '등급분포',
      render: (row) => {
        const entries = GRADE_ORDER.map((grade) => [grade, row.gradeDistribution[grade] ?? 0] as const).filter(
          ([, count]) => count > 0,
        );
        if (entries.length === 0) {
          return <span className="defect-list-table__empty">-</span>;
        }
        return (
          <span className="inspection-table__grade-group">
            {entries.map(([grade, count]) => (
              <span
                key={grade}
                className={`defect-list-table__grade ${GRADE_CLASSES[grade]}`}
                title={`${grade}등급 ${count}건`}
              >
                {grade}
                {count}
              </span>
            ))}
          </span>
        );
      },
    },
    {
      key: 'status',
      header: '상태',
      render: (row) => <span className="inspection-table__status">{INSPECTION_STATUS_LABEL[row.status]}</span>,
    },
    {
      key: 'assigneeName',
      header: '담당자',
      render: (row) => <span>{row.assigneeName ?? '-'}</span>,
    },
  ];
}

// 하자 목록(DefectListPage) "목록 보기" 탭 — 점검(Inspection) 단위 테이블(HAJA-393/394, #725/#726).
// 시각 디자인(DefectTable과 동일한 CSS 클래스 재사용)은 유지하되 로우 단위를 점검으로 재해석했다
// (사용자 확정 지시). 로딩/에러/빈 데이터 처리는 DefectTable과 동일 패턴.
export function InspectionTable({
  inspections,
  isLoading,
  isError,
  onRetry,
  selectedIds,
  onSelectionChange,
}: Props) {
  const navigate = useNavigate();
  const rows = useMemo(() => (inspections ?? []).map(toTableRow), [inspections]);
  const visibleIds = useMemo(() => rows.map((row) => row.id), [rows]);
  const selectedVisibleCount = visibleIds.filter((id) => selectedIds.has(id)).length;
  const isAllSelected = visibleIds.length > 0 && selectedVisibleCount === visibleIds.length;
  const isPartiallySelected = selectedVisibleCount > 0 && !isAllSelected;

  const handleToggleAll = () => {
    const next = new Set(selectedIds);
    if (visibleIds.every((id) => next.has(id))) {
      visibleIds.forEach((id) => next.delete(id));
    } else {
      visibleIds.forEach((id) => next.add(id));
    }
    onSelectionChange(next);
  };

  const handleToggleRow = (id: number) => {
    const next = new Set(selectedIds);
    if (next.has(id)) {
      next.delete(id);
    } else {
      next.add(id);
    }
    onSelectionChange(next);
  };

  const columns = createColumns({
    isAllSelected,
    isPartiallySelected,
    selectedIds,
    hasRows: rows.length > 0,
    onToggleAll: handleToggleAll,
    onToggleRow: handleToggleRow,
  });

  if (isLoading) {
    return (
      <div className="defect-list-table__loading" role="status">
        <span className="defect-list-table__loading-bar" />
        <span>점검 목록을 불러오는 중입니다</span>
      </div>
    );
  }

  if (isError) {
    return <ErrorFallback message="점검 목록을 불러오지 못했습니다." onRetry={onRetry} />;
  }

  return (
    <div className="defect-list-table">
      <Table
        columns={columns}
        data={rows}
        emptyMessage="조회된 점검이 없습니다. 필터 조건을 변경해 보세요."
        onRowClick={(row) => navigate(`/inspections/${row.id}/defects`)}
      />
    </div>
  );
}
