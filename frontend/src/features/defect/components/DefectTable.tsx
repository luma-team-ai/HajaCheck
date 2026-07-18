import { Link } from 'react-router-dom';
import { ErrorFallback } from '../../../shared/components/ErrorFallback';
import type { TableColumn } from '../../../shared/components/Table';
import { Table } from '../../../shared/components/Table';
import { DEFECT_GRADE_LABEL, DEFECT_STATUS_LABEL, type Defect } from '../types';

type Props = {
  defects: Defect[] | undefined;
  isLoading: boolean;
  isError: boolean;
  onRetry: () => void;
};

const COLUMNS: TableColumn<Defect>[] = [
  { key: 'typeLabel', header: '유형' },
  {
    key: 'grade',
    header: '등급',
    render: (row) => (row.grade ? `${row.grade} · ${DEFECT_GRADE_LABEL[row.grade]}` : '미분류'),
  },
  { key: 'status', header: '상태', render: (row) => DEFECT_STATUS_LABEL[row.status] },
  { key: 'facilityName', header: '시설물' },
  {
    key: 'createdAt',
    header: '발생일',
    render: (row) => row.createdAt.slice(0, 10),
  },
  {
    key: 'id',
    header: '상세',
    render: (row) => (
      <Link className="font-medium text-primary no-underline hover:underline" to={`/defects/${row.id}`}>
        상세보기
      </Link>
    ),
  },
];

// 로딩/에러 상태는 Table 바깥에서 처리하고, 빈 데이터는 Table의 emptyMessage로 위임(FacilityTable과 동일 패턴)
export function DefectTable({ defects, isLoading, isError, onRetry }: Props) {
  if (isLoading) {
    return (
      <div
        className="flex items-center justify-center px-4 py-12 text-sm text-text-muted"
        role="status"
      >
        불러오는 중...
      </div>
    );
  }

  if (isError) {
    return <ErrorFallback message="하자 목록을 불러오지 못했습니다." onRetry={onRetry} />;
  }

  return (
    <Table columns={COLUMNS} data={defects ?? []} emptyMessage="조회된 하자가 없습니다." />
  );
}
