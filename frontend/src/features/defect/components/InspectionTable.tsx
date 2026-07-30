import { useMemo } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ErrorFallback } from '../../../shared/components/ErrorFallback';
import type { TableColumn } from '../../../shared/components/Table';
import { Table } from '../../../shared/components/Table';
import { GRADE_CLASSES } from '../constants/defectPresentation';
import { EMPTY_GRADE_DISTRIBUTION, INSPECTION_STATUS_LABEL } from '../types';
import type { DefectGrade, InspectionListItem } from '../types';
import { formatInspectionCode } from '../utils/defectFormat';

type Props = {
  inspections: InspectionListItem[] | undefined;
  isLoading: boolean;
  isError: boolean;
  onRetry: () => void;
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

function toTableRow(inspection: InspectionListItem): InspectionTableRow {
  return {
    id: inspection.id,
    inspectionCode: formatInspectionCode(inspection.id),
    facilityName: inspection.facilityName,
    inspectionDate: inspection.inspectionDate,
    roundNo: inspection.roundNo,
    defectCount: inspection.defectCount,
    // #893 하드닝 — 백엔드 응답에 gradeDistribution이 없는(계약 불일치) row가 섞여 있어도
    // "Cannot read properties of undefined" 크래시 없이 등급분포를 전부 0으로 렌더한다.
    gradeDistribution: inspection.gradeDistribution ?? EMPTY_GRADE_DISTRIBUTION,
    status: inspection.status,
    assigneeName: inspection.assigneeName,
  };
}

function createColumns(): TableColumn<InspectionTableRow>[] {
  return [
    {
      key: 'inspectionCode',
      header: '점검 ID',
      render: (row) => (
        <Link
          aria-label="점검 상세보기"
          className="defect-list-table__id"
          to={`/inspections/${row.id}/defects`}
        >
          {row.inspectionCode}
        </Link>
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
// 기존 하자 목록 CSS 클래스를 재사용하되 로우 단위를 점검으로 재해석했다(사용자 확정 지시).
export function InspectionTable({
  inspections,
  isLoading,
  isError,
  onRetry,
}: Props) {
  const navigate = useNavigate();
  const rows = useMemo(() => (inspections ?? []).map(toTableRow), [inspections]);
  const columns = createColumns();

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
