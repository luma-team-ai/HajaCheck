import type { TableColumn } from '../../../shared/components/Table/Table';
import { Table } from '../../../shared/components/Table/Table';
import { TableFooterPagination } from '../../../shared/components/TableFooterPagination/TableFooterPagination';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import type { MyCounselAssignee, MyCounselRow } from '../types';
import { CounselStatusBadge } from './CounselStatusBadge';
import { CounselTypeBadge } from './CounselTypeBadge';

type Props = {
  rows: MyCounselRow[];
  isLoading: boolean;
  isError: boolean;
  currentPage: number;
  pageSize: number;
  totalItems: number;
  onPageChange: (page: number) => void;
  onPageSizeChange: (size: number) => void;
};

// 담당자 열 — 없으면 '-', 상담원이면 아바타(이니셜)+이름, '배정 대기중'/'관리자 그룹'은 텍스트만
// (Figma 시안). features/admin/components/UserAvatar.tsx의 이니셜 폴백 패턴을 참고했다 — 그
// 컴포넌트는 AdminUser 타입에 묶여 있어 이 feature 전용 MyCounselAssignee 타입과 맞지 않아 별도
// 컴포넌트를 새로 만드는 대신 이 파일 안에서 직접 렌더한다.
function CounselAssigneeCell({ assignee }: { assignee: MyCounselAssignee | null }) {
  if (!assignee) {
    return <span className="text-text-muted">-</span>;
  }
  if (assignee.textOnly) {
    return <span className="text-text-default">{assignee.name}</span>;
  }
  return (
    <span className="inline-flex items-center gap-2">
      <span
        className="inline-flex h-6 w-6 items-center justify-center rounded-full bg-neutral-100 text-xs font-medium text-text-default"
        aria-hidden="true"
      >
        {assignee.name.slice(0, 1)}
      </span>
      {assignee.name}
    </span>
  );
}

// '보기' 액션 — 상담 상세 뷰어로 이동할 실 라우트가 없어(BE 미구현, contract 부재) 항상 비활성
// 버튼으로 렌더한다(MyInspectionsTable의 "결과 보기"와 동일 패턴). Figma 시안은 canView=true인
// 행만 실제로 보이고, 나머지는 opacity-0로 자리만 차지한 채 숨긴다.
const COLUMNS: TableColumn<MyCounselRow>[] = [
  { key: 'type', header: '유형', render: (row) => <CounselTypeBadge type={row.type} /> },
  { key: 'topic', header: '주제' },
  {
    key: 'assignee',
    header: '담당자',
    render: (row) => <CounselAssigneeCell assignee={row.assignee} />,
  },
  {
    key: 'status',
    header: '상태',
    render: (row) => <CounselStatusBadge status={row.status} waitingNumber={row.waitingNumber} />,
  },
  { key: 'startedAt', header: '시작 일시' },
  {
    key: 'lastMessage',
    header: '마지막 메시지',
    render: (row) => (
      <span className="block max-w-xs truncate text-text-muted">{row.lastMessage}</span>
    ),
  },
  {
    key: 'id',
    header: 'ACTION',
    render: (row) => (
      <button
        type="button"
        className={`cursor-not-allowed text-sm font-semibold text-primary ${row.canView ? 'opacity-60' : 'opacity-0'}`}
        disabled
        aria-hidden={!row.canView}
        title={row.canView ? '상담 상세 뷰어 연동 후 지원 예정(BE 미구현)' : undefined}
      >
        보기
      </button>
    ),
  },
];

// 내 상담 내역 테이블 — MyInspectionsTable과 완전히 동일한 shared Table(범용) +
// TableFooterPagination(공통 페이지네이션) 조합. BE가 없어 페이지를 이동해도 mock 데이터
// (useMyCounsels)는 항상 같은 4건을 돌려준다(표시용 totalElements=18) — 후속 BE 연동 시 이
// 컴포넌트는 그대로 두고 훅만 실 서버에 붙이면 된다.
export function MyCounselsTable({
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
    return <p className="py-6 text-sm text-danger">상담 내역을 불러오지 못했습니다.</p>;
  }

  return (
    <div className="overflow-hidden rounded-2xl border border-border">
      <div className="overflow-x-auto">
        <Table columns={COLUMNS} data={rows} emptyMessage="상담 내역이 없습니다" />
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
