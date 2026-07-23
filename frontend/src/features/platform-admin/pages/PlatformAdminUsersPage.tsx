import { useEffect, useMemo, useRef, useState } from 'react';
import { Button } from '../../../shared/components/Button';
import { TableFooterPagination } from '../../../shared/components/TableFooterPagination/TableFooterPagination';
import { useDebouncedValue } from '../../../shared/hooks/useDebouncedValue';
import { fetchAllPlatformAdminUsers } from '../api/platformAdminUserApi';
import { AdminUserFilterBar } from '../components/AdminUserFilterBar';
import type { FilterValue } from '../components/AdminUserFilterBar';
import { AdminUserPrintTable } from '../components/AdminUserPrintTable';
import type { AdminUserRowAction } from '../components/AdminUserRowMenu';
import { AdminUserStatsCard } from '../components/AdminUserStatsCard';
import { AdminUserTable } from '../components/AdminUserTable';
import { CreateUserModal } from '../components/CreateUserModal';
import { RoleChangeModal } from '../components/RoleChangeModal';
import { StatusChangeModal } from '../components/StatusChangeModal';
import { DEFAULT_PAGE_SIZE, EMPTY_CELL, ROLE_LABEL, STATUS_LABEL } from '../constants';
import { usePlatformAdminUsers } from '../hooks/usePlatformAdminUsers';
import { useChangeUserRole } from '../hooks/useChangeUserRole';
import { useChangeUserStatus } from '../hooks/useChangeUserStatus';
import { useCreateUser } from '../hooks/useCreateUser';
import type { AdminUser, AdminUserRole, AdminUserStatus } from '../types';
import { DownloadIcon } from '../components/icons/DownloadIcon';
import { InviteIcon } from '../components/icons/InviteIcon';

const KEYWORD_DEBOUNCE_MS = 300;
const NOTICE_AUTO_DISMISS_MS = 2500;

// 플랫폼 관리자 > 사용자 관리(#577) — features/admin/pages/AdminUsersPage.tsx(Figma node-id
// 177-2017, #405)를 그대로 옮긴 것. 기업 관리자 화면은 요청 관리자 회사로 스코프되지만, 이 화면은
// PLATFORM_ADMIN 전용 GET/PATCH/POST /api/platform-admin/users(회사 스코프 없음)를 호출한다
// (백엔드 신규 엔드포인트는 backend/576-platform-admin-users 워크트리에서 별도 구현).
// 헤더(브레드크럼)·사이드바는 PlatformAdminShellRoute가 담당하므로 이 페이지는 CONTENT 영역만 그린다.
export function PlatformAdminUsersPage() {
  const [keywordInput, setKeywordInput] = useState('');
  const keyword = useDebouncedValue(keywordInput, KEYWORD_DEBOUNCE_MS);
  const [role, setRole] = useState<FilterValue<AdminUserRole>>('');
  const [status, setStatus] = useState<FilterValue<AdminUserStatus>>('');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [notice, setNotice] = useState<string | null>(null);
  const [roleModalUser, setRoleModalUser] = useState<AdminUser | null>(null);
  const [statusModalUser, setStatusModalUser] = useState<AdminUser | null>(null);
  const [isCreateModalOpen, setIsCreateModalOpen] = useState(false);
  const [isExporting, setIsExporting] = useState(false);
  const [exportUsers, setExportUsers] = useState<AdminUser[] | null>(null);
  const [exportGeneratedAt, setExportGeneratedAt] = useState('');
  const {
    changeRole,
    isPending: isRoleChanging,
    error: roleChangeError,
    resetError: resetRoleChangeError,
  } = useChangeUserRole();
  const {
    changeStatus,
    isPending: isStatusChanging,
    error: statusChangeError,
    resetError: resetStatusChangeError,
  } = useChangeUserStatus();
  const {
    createUser,
    isPending: isCreatingUser,
    error: createUserError,
    resetError: resetCreateUserError,
  } = useCreateUser();

  // 필터·검색어·페이지 크기가 바뀌면 1페이지로 되돌린다 — AdminUsersPage와 동일한 이유(#378 리뷰 지적).
  const filterSignature = `${keyword}|${role}|${status}|${pageSize}`;
  const prevFilterSignatureRef = useRef(filterSignature);
  if (prevFilterSignatureRef.current !== filterSignature) {
    prevFilterSignatureRef.current = filterSignature;
    setPage(1);
  }

  useEffect(() => {
    if (!notice) {
      return;
    }
    const timer = setTimeout(() => setNotice(null), NOTICE_AUTO_DISMISS_MS);
    return () => clearTimeout(timer);
  }, [notice]);

  useEffect(() => {
    if (!exportUsers) {
      return;
    }
    function handleAfterPrint() {
      setExportUsers(null);
    }
    window.addEventListener('afterprint', handleAfterPrint);
    window.print();
    return () => window.removeEventListener('afterprint', handleAfterPrint);
  }, [exportUsers]);

  const params = useMemo(
    () => ({
      page,
      size: pageSize,
      ...(keyword ? { keyword } : {}),
      ...(role ? { role } : {}),
      ...(status ? { status } : {}),
    }),
    [page, pageSize, keyword, role, status],
  );

  const { data, isLoading, isError, refetch } = usePlatformAdminUsers(params);

  const users = data?.content ?? [];
  const totalElements = data?.totalElements ?? 0;
  const totalPages = Math.max(1, Math.ceil(totalElements / pageSize));

  function handleReset() {
    setKeywordInput('');
    setRole('');
    setStatus('');
  }

  function handleRowAction(action: AdminUserRowAction, user: AdminUser) {
    if (action === 'CHANGE_ROLE') {
      setRoleModalUser(user);
      return;
    }
    setStatusModalUser(user);
  }

  function handleCloseRoleModal() {
    setRoleModalUser(null);
    resetRoleChangeError();
  }

  function handleCloseStatusModal() {
    setStatusModalUser(null);
    resetStatusChangeError();
  }

  async function handleRoleConfirm(user: AdminUser, newRole: AdminUserRole) {
    await changeRole({ id: user.id, role: newRole });
    setRoleModalUser(null);
    setNotice(`${user.name ?? user.email} · 역할이 ${ROLE_LABEL[newRole]}(으)로 변경되었습니다`);
  }

  async function handleStatusConfirm(user: AdminUser, newStatus: AdminUserStatus) {
    await changeStatus({ id: user.id, status: newStatus });
    setStatusModalUser(null);
    setNotice(`${user.name ?? user.email} · 상태가 ${STATUS_LABEL[newStatus]}(으)로 변경되었습니다`);
  }

  async function handleExport() {
    setIsExporting(true);
    try {
      const all = await fetchAllPlatformAdminUsers({
        ...(keyword ? { keyword } : {}),
        ...(role ? { role } : {}),
        ...(status ? { status } : {}),
      });
      setExportGeneratedAt(new Date().toLocaleString('ko-KR'));
      setExportUsers(all);
    } catch {
      setNotice('내보내기에 실패했습니다. 잠시 후 다시 시도해 주세요.');
    } finally {
      setIsExporting(false);
    }
  }

  function handleCloseCreateModal() {
    setIsCreateModalOpen(false);
    resetCreateUserError();
  }

  async function handleCreateUserConfirm(input: {
    email: string;
    password: string;
    name: string;
    role: AdminUserRole;
  }) {
    const created = await createUser(input);
    setIsCreateModalOpen(false);
    setNotice(`${created.name ?? created.email} · 사용자가 등록되었습니다`);
  }

  return (
    <>
      <div className="flex min-h-full flex-col gap-6 bg-surface-muted p-6 sm:p-8 print:hidden">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h1 className="m-0 flex items-baseline gap-3">
            <span className="text-2xl font-bold text-heading">사용자 관리</span>
            <span className="text-sm text-text-muted">
              전체 {data ? data.stats.totalMembers.toLocaleString('ko-KR') : EMPTY_CELL}명
            </span>
          </h1>
          <div className="flex items-center gap-2">
            <Button
              variant="secondary"
              size="sm"
              onClick={() => void handleExport()}
              disabled={isExporting}
            >
              <DownloadIcon />
              {isExporting ? '내보내는 중...' : '내보내기'}
            </Button>
            <Button variant="primary" size="sm" onClick={() => setIsCreateModalOpen(true)}>
              <InviteIcon />+ 사용자 등록
            </Button>
          </div>
        </div>

        <AdminUserStatsCard stats={data?.stats} isError={isError} />

        <AdminUserFilterBar
          keyword={keywordInput}
          role={role}
          status={status}
          onKeywordChange={setKeywordInput}
          onRoleChange={setRole}
          onStatusChange={setStatus}
          onReset={handleReset}
        />

        <div className="overflow-hidden rounded-[20px] border border-border bg-surface">
          <AdminUserTable
            users={users}
            isLoading={isLoading}
            isError={isError}
            onRetry={() => void refetch()}
            onRowAction={handleRowAction}
          />
          <TableFooterPagination
            pageSize={pageSize}
            onPageSizeChange={setPageSize}
            currentPage={page}
            totalPages={totalPages}
            totalItems={totalElements}
            onPageChange={setPage}
          />
        </div>

        <CreateUserModal
          open={isCreateModalOpen}
          onClose={handleCloseCreateModal}
          onConfirm={handleCreateUserConfirm}
          isSubmitting={isCreatingUser}
          submitErrorMessage={createUserError?.message}
        />
        <RoleChangeModal
          user={roleModalUser}
          onClose={handleCloseRoleModal}
          onConfirm={handleRoleConfirm}
          isSubmitting={isRoleChanging}
          submitErrorMessage={roleChangeError?.message}
        />
        <StatusChangeModal
          user={statusModalUser}
          onClose={handleCloseStatusModal}
          onConfirm={handleStatusConfirm}
          isSubmitting={isStatusChanging}
          submitErrorMessage={statusChangeError?.message}
        />

        {notice && (
          <div className="pointer-events-none fixed inset-0 z-[1000] flex items-center justify-center">
            <div
              role="status"
              aria-live="polite"
              className="rounded-[20px] border border-border bg-white/90 px-6 py-4 text-sm font-medium text-text-default shadow-[0px_20px_25px_-5px_rgba(0,0,0,0.1),0px_8px_10px_-6px_rgba(0,0,0,0.1)] backdrop-blur-[10px]"
            >
              {notice}
            </div>
          </div>
        )}
      </div>
      {exportUsers && <AdminUserPrintTable users={exportUsers} generatedAt={exportGeneratedAt} />}
    </>
  );
}
