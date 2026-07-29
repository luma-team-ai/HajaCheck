import { Link } from 'react-router-dom';
import type { TableColumn } from '../../../shared/components/Table/Table';
import { Table } from '../../../shared/components/Table/Table';
import { TableFooterPagination } from '../../../shared/components/TableFooterPagination/TableFooterPagination';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import type { InspectionHistoryRow } from '../types';
import { formatInspectionDate, formatRoundLabel } from '../utils/myInspectionsFormat';
import { InspectionRoleBadge } from './InspectionRoleBadge';
import { InspectionStatusBadge } from './InspectionStatusBadge';

type Props = {
  rows: InspectionHistoryRow[];
  isLoading: boolean;
  isError: boolean;
  currentPage: number;
  pageSize: number;
  totalItems: number;
  onPageChange: (page: number) => void;
  onPageSizeChange: (size: number) => void;
};

// "결과 보기" 링크 — 점검 회차 id로 분석 결과 뷰어(/inspections/:id/viewer, ResultViewerPage,
// router.tsx)로 이동한다(#1236). implementedRoutes.ts의 INSPECTION_ID_SCOPED_ROUTE 화이트리스트가
// 이미 이 패턴을 커버한다.
const COLUMNS: TableColumn<InspectionHistoryRow>[] = [
  { key: 'facilityName', header: '시설물' },
  {
    key: 'roundNo',
    header: '회차',
    render: (row) => formatRoundLabel(row.inspectionDate, row.roundNo),
  },
  {
    key: 'inspectionDate',
    header: '점검일',
    render: (row) => formatInspectionDate(row.inspectionDate),
  },
  { key: 'role', header: '내 역할', render: (row) => <InspectionRoleBadge role={row.role} /> },
  { key: 'defectCount', header: '하자 수', render: (row) => `${row.defectCount}건` },
  { key: 'status', header: '상태', render: (row) => <InspectionStatusBadge status={row.status} /> },
  {
    key: 'id',
    header: 'ACTION',
    render: (row) => (
      <Link
        to={`/inspections/${row.id}/viewer`}
        className="text-sm font-semibold text-primary no-underline hover:underline"
      >
        결과 보기
      </Link>
    ),
  },
];

// 내 점검 이력 테이블 — shared Table(범용) + TableFooterPagination(공통 페이지네이션) 재사용.
// BE가 없어 페이지를 이동해도 mock 데이터(useMyInspections)는 항상 같은 8건을 돌려준다
// (표시용 totalElements=18) — 후속 BE 연동 시 이 컴포넌트는 그대로 두고 훅만 실 서버에 붙이면 된다.
export function MyInspectionsTable({
  rows,
  isLoading,
  isError,
  currentPage,
  pageSize,
  totalItems,
  onPageChange,
  onPageSizeChange,
}: Props) {
  const totalPages = Math.max(1, Math.ceil(totalItems / pageSize));

  if (isLoading) {
    return <LoadingSpinner className="flex items-center justify-start gap-2 py-6" />;
  }

  if (isError) {
    return <p className="py-6 text-sm text-danger">점검 이력을 불러오지 못했습니다.</p>;
  }

  return (
    <div className="overflow-hidden rounded-2xl border border-border">
      <div className="overflow-x-auto">
        <Table columns={COLUMNS} data={rows} emptyMessage="참여한 점검 이력이 없습니다" />
      </div>
      <TableFooterPagination
        pageSize={pageSize}
        pageSizeOptions={[8, 20, 50]}
        onPageSizeChange={onPageSizeChange}
        currentPage={currentPage}
        totalPages={totalPages}
        totalItems={totalItems}
        onPageChange={onPageChange}
      />
    </div>
  );
}
