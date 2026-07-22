import { ROLE_BADGE_CLASS, ROLE_LABEL, STATUS_DOT_CLASS, STATUS_LABEL } from '../constants';
import type { AdminUser } from '../types';
import { formatJoinedAt, formatRelativeAccess } from '../utils/formatUserDates';
import { AdminUserRowMenu } from './AdminUserRowMenu';
import type { AdminUserRowAction } from './AdminUserRowMenu';
import { StateRow } from './StateRow';
import { UserAvatar } from './UserAvatar';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';

interface AdminUserTableProps {
  users: AdminUser[];
  isLoading: boolean;
  isError: boolean;
  onRetry: () => void;
  onRowAction: (action: AdminUserRowAction, user: AdminUser) => void;
}

const COLUMN_COUNT = 6;
const HEADER_CELL = 'px-4 py-3 text-left text-[13px] font-medium text-text-muted';
const BODY_CELL = 'px-4 py-3 align-middle';

// 사용자 목록 표 — Figma node-id 177-2017.
// 공통 Table(shared/components/Table)은 columns/data만 받는 단순 구조라 행 액션 메뉴·아바타 셀을
// 표현할 수 없다(TableColumn.key가 keyof T로 묶여 있어 데이터 필드가 아닌 컬럼을 못 만든다).
// 공통 컴포넌트 확장은 shared 오너 리뷰가 필요한 별건이므로, 이번 화면은 feature-local 마크업으로 둔다
// (MyPlanPage가 공용 레이아웃 클래스 대신 feature 전용 마크업을 쓴 것과 같은 판단).
export function AdminUserTable({
  users,
  isLoading,
  isError,
  onRetry,
  onRowAction,
}: AdminUserTableProps) {
  return (
    <table className="w-full border-collapse text-sm">
      <thead>
        <tr className="border-b border-border">
          <th className={`${HEADER_CELL} pl-6`}>이름</th>
          <th className={HEADER_CELL}>이메일</th>
          <th className={HEADER_CELL}>역할</th>
          <th className={HEADER_CELL}>가입일</th>
          <th className={HEADER_CELL}>최근 접속</th>
          <th className={`${HEADER_CELL} pr-6`}>상태</th>
        </tr>
      </thead>
      <tbody>
        {isLoading && (
          <StateRow colSpan={COLUMN_COUNT}>
            <LoadingSpinner className="py-0" />
          </StateRow>
        )}

        {!isLoading && isError && (
          <StateRow colSpan={COLUMN_COUNT}>
            <span className="flex flex-col items-center gap-3" role="alert">
              <span className="text-danger">
                사용자 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
              </span>
              <button
                type="button"
                className="cursor-pointer rounded-full border border-border bg-surface px-4 py-1.5 text-[13px] text-text-default hover:text-primary"
                onClick={onRetry}
              >
                다시 시도
              </button>
            </span>
          </StateRow>
        )}

        {!isLoading && !isError && users.length === 0 && (
          <StateRow colSpan={COLUMN_COUNT}>
            <span className="text-text-muted">조건에 맞는 사용자가 없습니다</span>
          </StateRow>
        )}

        {!isLoading &&
          !isError &&
          users.map((user) => (
            <tr key={user.id} className="border-b border-border last:border-b-0 hover:bg-surface-muted">
              <td className={`${BODY_CELL} pl-6`}>
                <span className="flex items-center gap-2.5">
                  <UserAvatar user={user} />
                  <span className="text-heading">{user.name}</span>
                </span>
              </td>
              <td className={`${BODY_CELL} text-text-default`}>{user.email}</td>
              <td className={BODY_CELL}>
                <span
                  className={`inline-flex rounded-full px-2.5 py-1 text-xs ${ROLE_BADGE_CLASS[user.role]}`}
                >
                  {ROLE_LABEL[user.role]}
                </span>
              </td>
              <td className={`${BODY_CELL} text-text-default`}>{formatJoinedAt(user.joinedAt)}</td>
              <td className={`${BODY_CELL} text-text-default`}>
                {formatRelativeAccess(user.lastAccessAt)}
              </td>
              <td className={`${BODY_CELL} pr-6`}>
                <span className="flex items-center justify-between gap-2">
                  <span className="flex items-center gap-1.5 text-[13px]">
                    <span
                      className={`inline-block h-1.5 w-1.5 rounded-full ${STATUS_DOT_CLASS[user.status]}`}
                      aria-hidden
                    />
                    {STATUS_LABEL[user.status]}
                  </span>
                  <AdminUserRowMenu user={user} onAction={onRowAction} />
                </span>
              </td>
            </tr>
          ))}
      </tbody>
    </table>
  );
}
