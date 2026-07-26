import { ErrorFallback } from '../../../shared/components/ErrorFallback';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import type { TableColumn } from '../../../shared/components/Table';
import { Table } from '../../../shared/components/Table';
import type { Facility } from '../types';

type Props = {
  facilities: Facility[] | undefined;
  isLoading: boolean;
  isError: boolean;
  onRetry: () => void;
  // latestDefectId — 하자 오버레이 직행(HAJA-434 갭1) 라우팅 판단용, 하자 없으면 null.
  onSelectFacility: (id: number, latestDefectId: number | null) => void;
  // 검색/필터 적용 결과 0건일 때 "등록된 시설물이 없습니다"(#810 이전 문구)는 오해를 줄 수 있어
  // FacilityListPage가 필터 활성 여부에 따라 다른 안내 문구를 넘길 수 있게 열어둔다.
  emptyMessage?: string;
};

// 로딩/에러 상태는 Table 바깥에서 처리하고, 빈 데이터는 Table의 emptyMessage로 위임(React_코드_컨벤션.md §5 4상태)
// 공용 Table(shared, 미수정 대상)은 행 전체 클릭(onRowClick)을 지원하지 않아, 이름 셀을 버튼으로 렌더링해
// 시설물 상세(/facilities/:id)로 이동하는 진입점을 제공한다(#489).
export function FacilityTable({
  facilities,
  isLoading,
  isError,
  onRetry,
  onSelectFacility,
  emptyMessage = '등록된 시설물이 없습니다. 시설물을 등록해 주세요.',
}: Props) {
  if (isLoading) {
    return (
      <LoadingSpinner className="flex items-center justify-center gap-2 px-4 py-12" />
    );
  }

  if (isError) {
    return <ErrorFallback message="시설물 목록을 불러오지 못했습니다." onRetry={onRetry} />;
  }

  const columns: TableColumn<Facility>[] = [
    {
      key: 'name',
      header: '이름',
      render: (row) => (
        <button
          type="button"
          onClick={() => onSelectFacility(row.id, row.latestDefectId)}
          className="cursor-pointer bg-transparent p-0 text-left font-semibold text-accent underline-offset-2 hover:underline"
        >
          {row.name}
        </button>
      ),
    },
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

  return (
    <Table columns={columns} data={facilities ?? []} emptyMessage={emptyMessage} />
  );
}
