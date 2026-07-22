import { useEffect, useMemo, useRef, useState } from 'react';
import { Button } from '../../../shared/components/Button';
import { TableFooterPagination } from '../../../shared/components/TableFooterPagination/TableFooterPagination';
import { useDebouncedValue } from '../../../shared/hooks/useDebouncedValue';
import { fetchAllAdminUsers } from '../api/adminApi';
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
import { useAdminUsers } from '../hooks/useAdminUsers';
import { useChangeUserRole } from '../hooks/useChangeUserRole';
import { useChangeUserStatus } from '../hooks/useChangeUserStatus';
import { useCreateUser } from '../hooks/useCreateUser';
import type { AdminUser, AdminUserRole, AdminUserStatus } from '../types';
import { DownloadIcon } from '../components/icons/DownloadIcon';
import { InviteIcon } from '../components/icons/InviteIcon';

const KEYWORD_DEBOUNCE_MS = 300;
const NOTICE_AUTO_DISMISS_MS = 2500;

// 관리자 > 사용자 관리 — Figma node-id 177-2017 "hajaCheck Admin - 사용자 관리 워크스페이스".
// 헤더(브레드크럼)·사이드바는 AppShellRoute → AppLayout이 담당하므로 이 페이지는 CONTENT 영역만 그린다.
export function AdminUsersPage() {
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

  // 필터·검색어·페이지 크기가 바뀌면 1페이지로 되돌린다 — 3페이지에서 필터를 좁혀 결과가
  // 1페이지뿐이 되면 빈 화면이 뜨기 때문.
  // useEffect가 아니라 렌더 중 동기 조정(React 공식 패턴, 컨벤션 §5 "파생값은 렌더 중 계산으로")으로
  // 처리한다 — effect에서 하면 "구 페이지 + 신 필터" 조합으로 쿼리가 한 번 나간 뒤에야 페이지가
  // 바뀌어, 결과가 있는데도 "조건에 맞는 사용자가 없습니다"가 한 프레임 깜빡인다(#378 리뷰 지적).
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

  // 내보내기 데이터가 준비되면(인쇄 전용 표가 실제로 렌더된 뒤) 인쇄 대화상자를 띄운다.
  // "대상: PDF로 저장"을 사용자가 고르면 곧 리스트가 PDF로 저장된다(라이브러리 없이 브라우저 인쇄 경로).
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

  const { data, isLoading, isError, refetch } = useAdminUsers(params);

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
    // 모달을 다시 열 때 지난 실패의 에러 메시지가 즉시 재노출되지 않도록 초기화
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
    // 실패 시 여기서 잡지 않고 그대로 전파한다 — RoleChangeModal이 모달을 열어둔 채
    // submitErrorMessage(roleChangeError)를 보여줘야 하므로.
  }

  async function handleStatusConfirm(user: AdminUser, newStatus: AdminUserStatus) {
    await changeStatus({ id: user.id, status: newStatus });
    setStatusModalUser(null);
    setNotice(`${user.name ?? user.email} · 상태가 ${STATUS_LABEL[newStatus]}(으)로 변경되었습니다`);
  }

  async function handleExport() {
    setIsExporting(true);
    try {
      // 화면 페이지네이션과 무관하게 "현재 필터에 걸리는 전체 목록"을 내보낸다.
      const all = await fetchAllAdminUsers({
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
    // 실패 시 여기서 잡지 않고 그대로 전파한다 — CreateUserModal이 모달을 열어둔 채
    // submitErrorMessage(createUserError)를 보여줘야 하므로.
  }

  return (
    <>
      <div className="flex min-h-full flex-col gap-6 bg-surface-muted p-6 sm:p-8 print:hidden">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h1 className="m-0 flex items-baseline gap-3">
            <span className="text-2xl font-bold text-heading">사용자 관리</span>
            {/* 자리는 항상 지키되 집계가 없으면 "-" — 로딩 중 "전체 0명"은 제목 옆 문구라 지표 타일의
                0(플레이스홀더 관습)과 달리 사실 단언으로 읽힌다. 실패든 미도착이든 모르는 건 모른다고 둔다 */}
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

        {/* data 유무와 무관하게 항상 렌더 — 조회 전에는 0, 실패 시에는 "-"로 자리를 지킨다 */}
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
