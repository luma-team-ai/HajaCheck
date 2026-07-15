import { ErrorFallback } from '../../../shared/components/ErrorFallback';
import type { TableColumn } from '../../../shared/components/Table';
import { Table } from '../../../shared/components/Table';
import type { Facility } from '../types';

type Props = {
  facilities: Facility[] | undefined;
  isLoading: boolean;
  isError: boolean;
  onRetry: () => void;
};

const COLUMNS: TableColumn<Facility>[] = [
  { key: 'name', header: '이름' },
  { key: 'type', header: '유형' },
  { key: 'address', header: '주소', render: (row) => row.address ?? '-' },
  { key: 'scale', header: '규모', render: (row) => row.scale ?? '-' },
  {
    key: 'inspectionCycleMonths',
    header: '점검주기',
    render: (row) => (row.inspectionCycleMonths != null ? `${row.inspectionCycleMonths}개월` : '-'),
  },
  {
    key: 'nextInspectionDueAt',
    header: '다음점검일',
    render: (row) => row.nextInspectionDueAt ?? '-',
  },
];

// 로딩/에러 상태는 Table 바깥에서 처리하고, 빈 데이터는 Table의 emptyMessage로 위임(React_코드_컨벤션.md §5 4상태)
export function FacilityTable({ facilities, isLoading, isError, onRetry }: Props) {
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
    return <ErrorFallback message="시설물 목록을 불러오지 못했습니다." onRetry={onRetry} />;
  }

  return (
    <Table
      columns={COLUMNS}
      data={facilities ?? []}
      emptyMessage="등록된 시설물이 없습니다. 시설물을 등록해 주세요."
    />
  );
}
