import { useState } from 'react';
import {
  EMPTY_CELL,
  PLAN_BADGE_CLASS,
  PLAN_LABEL,
  ROLE_BADGE_CLASS,
  ROLE_LABEL,
  STATUS_DOT_CLASS,
  STATUS_LABEL,
} from '../constants';
import type { AdminUser } from '../types';
import { formatJoinedAt, formatRelativeAccess } from '../utils/formatUserDates';
import { AdminUserRowMenu } from './AdminUserRowMenu';
import type { AdminUserRowAction } from './AdminUserRowMenu';
import { StateRow } from './StateRow';
import { UserAvatar } from './UserAvatar';

interface AdminUserTableProps {
  users: AdminUser[];
  isLoading: boolean;
  isError: boolean;
  onRetry: () => void;
  onRowAction: (action: AdminUserRowAction, user: AdminUser) => void;
}

const COLUMN_COUNT = 8;
const HEADER_CELL = 'px-4 py-3 text-left text-[13px] font-medium text-text-muted';
const BODY_CELL = 'px-4 py-3 align-middle';

// 사용자 목록 표 — Figma node-id 177-2017.
// 공통 Table(shared/components/Table)은 columns/data만 받는 단순 구조라 행 선택 체크박스·행 액션 메뉴·
// 아바타 셀을 표현할 수 없다(TableColumn.key가 keyof T로 묶여 있어 데이터 필드가 아닌 컬럼을 못 만든다).
// 공통 컴포넌트 확장은 shared 오너 리뷰가 필요한 별건이므로, 이번 화면은 feature-local 마크업으로 둔다
// (MyPlanPage가 공용 레이아웃 클래스 대신 feature 전용 마크업을 쓴 것과 같은 판단).
export function AdminUserTable({
  users,
  isLoading,
  isError,
  onRetry,
  onRowAction,
}: AdminUserTableProps) {
  const [selectedIds, setSelectedIds] = useState<number[]>([]);

  // 현재 페이지에 실제로 보이는 행만 선택으로 인정한다 — 페이지 이동·필터 변경으로 사라진 id가
  // 남아 있어도 헤더 체크박스 상태가 어긋나지 않는다(별도 초기화 effect 불필요).
  const visibleSelectedIds = selectedIds.filter((id) => users.some((user) => user.id === id));
  const isAllSelected = users.length > 0 && visibleSelectedIds.length === users.length;

  // 현재 페이지 id만 추가/제거한다 — selectedIds를 통째로 교체하면 다른 페이지에서
  // 선택해둔 항목이 조용히 사라진다(#378 리뷰 지적).
  function handleToggleAll() {
    setSelectedIds((current) => {
      const otherPagesSelectedIds = current.filter((id) => !users.some((user) => user.id === id));
      return isAllSelected
        ? otherPagesSelectedIds
        : [...otherPagesSelectedIds, ...users.map((user) => user.id)];
    });
  }

  function handleToggleRow(id: number) {
    setSelectedIds((current) =>
      current.includes(id) ? current.filter((selected) => selected !== id) : [...current, id],
    );
  }

  return (
    <table className="w-full border-collapse text-sm">
      <thead>
        <tr className="border-b border-border">
          <th className={`${HEADER_CELL} w-12 pl-6`}>
            <input
              type="checkbox"
              className="h-4 w-4 cursor-pointer accent-primary"
              checked={isAllSelected}
              onChange={handleToggleAll}
              disabled={users.length === 0}
              aria-label="전체 선택"
            />
          </th>
          <th className={HEADER_CELL}>사용자</th>
          <th className={HEADER_CELL}>이메일</th>
          <th className={HEADER_CELL}>역할</th>
          <th className={HEADER_CELL}>플랜</th>
          <th className={HEADER_CELL}>가입일</th>
          <th className={HEADER_CELL}>최근 접속</th>
          <th className={`${HEADER_CELL} pr-6`}>상태</th>
        </tr>
      </thead>
      <tbody>
        {isLoading && (
          <StateRow colSpan={COLUMN_COUNT}>
            <span className="text-text-muted">불러오는 중...</span>
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
          users.map((user) => {
            const isSelected = visibleSelectedIds.includes(user.id);

            return (
              <tr
                key={user.id}
                className="border-b border-border last:border-b-0 hover:bg-surface-muted"
              >
                <td className={`${BODY_CELL} pl-6`}>
                  <input
                    type="checkbox"
                    className="h-4 w-4 cursor-pointer accent-primary"
                    checked={isSelected}
                    onChange={() => handleToggleRow(user.id)}
                    aria-label={`${user.name} 선택`}
                  />
                </td>
                <td className={BODY_CELL}>
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
                <td className={BODY_CELL}>
                  {user.plan ? (
                    <span
                      className={`inline-flex rounded-full border bg-surface px-2.5 py-1 text-xs ${PLAN_BADGE_CLASS[user.plan]}`}
                    >
                      {PLAN_LABEL[user.plan]}
                    </span>
                  ) : (
                    // 활성 구독(user_plans) 행이 없는 사용자 — 배지 없이 빈 셀로 표시
                    <span className="text-text-muted">{EMPTY_CELL}</span>
                  )}
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
            );
          })}
      </tbody>
    </table>
  );
}
