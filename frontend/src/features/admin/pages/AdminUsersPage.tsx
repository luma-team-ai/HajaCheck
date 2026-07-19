import { useEffect, useMemo, useRef, useState } from 'react';
import { Button } from '../../../shared/components/Button';
import { TableFooterPagination } from '../../../shared/components/TableFooterPagination/TableFooterPagination';
import { AdminUserFilterBar } from '../components/AdminUserFilterBar';
import type { FilterValue } from '../components/AdminUserFilterBar';
import type { AdminUserRowAction } from '../components/AdminUserRowMenu';
import { AdminUserStatsCard } from '../components/AdminUserStatsCard';
import { AdminUserTable } from '../components/AdminUserTable';
import { DEFAULT_PAGE_SIZE, EMPTY_CELL } from '../constants';
import { useAdminUsers } from '../hooks/useAdminUsers';
import type { AdminUser, AdminUserPlan, AdminUserRole, AdminUserStatus } from '../types';
import { DownloadIcon } from '../components/icons/DownloadIcon';
import { InviteIcon } from '../components/icons/InviteIcon';

const KEYWORD_DEBOUNCE_MS = 300;
const NOTICE_AUTO_DISMISS_MS = 2500;

// 행 액션(권한 변경·플랜 변경·이력 보기)은 아직 백엔드 API가 없다. 조용히 무시하면 눌러도
// 아무 일이 안 일어나 버그처럼 보이므로, SideNavBar의 미구현 안내와 같은 방식으로 알린다.
const ACTION_NOTICE: Record<AdminUserRowAction, string> = {
  CHANGE_ROLE: '권한 변경은 준비 중입니다',
  CHANGE_PLAN: '플랜 변경은 준비 중입니다',
  VIEW_HISTORY: '이력 보기는 준비 중입니다',
};

// 관리자 > 사용자 관리 — Figma node-id 177-2017 "hajaCheck Admin - 사용자 관리 워크스페이스".
// 헤더(브레드크럼)·사이드바는 AppShellRoute → AppLayout이 담당하므로 이 페이지는 CONTENT 영역만 그린다.
export function AdminUsersPage() {
  const [keywordInput, setKeywordInput] = useState('');
  const [keyword, setKeyword] = useState('');
  const [role, setRole] = useState<FilterValue<AdminUserRole>>('');
  const [plan, setPlan] = useState<FilterValue<AdminUserPlan>>('');
  const [status, setStatus] = useState<FilterValue<AdminUserStatus>>('');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [notice, setNotice] = useState<string | null>(null);

  // 타이핑마다 조회하지 않도록 검색어만 디바운스한다(드롭다운 필터는 즉시 반영)
  useEffect(() => {
    const timer = setTimeout(() => setKeyword(keywordInput), KEYWORD_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [keywordInput]);

  // 필터·검색어·페이지 크기가 바뀌면 1페이지로 되돌린다 — 3페이지에서 필터를 좁혀 결과가
  // 1페이지뿐이 되면 빈 화면이 뜨기 때문.
  // useEffect가 아니라 렌더 중 동기 조정(React 공식 패턴, 컨벤션 §5 "파생값은 렌더 중 계산으로")으로
  // 처리한다 — effect에서 하면 "구 페이지 + 신 필터" 조합으로 쿼리가 한 번 나간 뒤에야 페이지가
  // 바뀌어, 결과가 있는데도 "조건에 맞는 사용자가 없습니다"가 한 프레임 깜빡인다(#378 리뷰 지적).
  const filterSignature = `${keyword}|${role}|${plan}|${status}|${pageSize}`;
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

  const params = useMemo(
    () => ({
      page,
      size: pageSize,
      ...(keyword ? { keyword } : {}),
      ...(role ? { role } : {}),
      ...(plan ? { plan } : {}),
      ...(status ? { status } : {}),
    }),
    [page, pageSize, keyword, role, plan, status],
  );

  const { data, isLoading, isError, refetch } = useAdminUsers(params);

  const users = data?.content ?? [];
  const totalElements = data?.totalElements ?? 0;
  const totalPages = Math.max(1, Math.ceil(totalElements / pageSize));

  function handleReset() {
    setKeywordInput('');
    setKeyword('');
    setRole('');
    setPlan('');
    setStatus('');
  }

  function handleRowAction(action: AdminUserRowAction, user: AdminUser) {
    setNotice(`${user.name ?? user.email} · ${ACTION_NOTICE[action]}`);
  }

  return (
    <div className="flex min-h-full flex-col gap-6 bg-surface-muted p-6 sm:p-8">
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
          <Button variant="secondary" size="sm" onClick={() => setNotice('내보내기는 준비 중입니다')}>
            <DownloadIcon />
            내보내기
          </Button>
          <Button
            variant="primary"
            size="sm"
            onClick={() => setNotice('관리자 초대는 준비 중입니다')}
          >
            <InviteIcon />+ 관리자 초대
          </Button>
        </div>
      </div>

      {/* data 유무와 무관하게 항상 렌더 — 조회 전에는 0, 실패 시에는 "-"로 자리를 지킨다 */}
      <AdminUserStatsCard stats={data?.stats} isError={isError} />

      <AdminUserFilterBar
        keyword={keywordInput}
        role={role}
        plan={plan}
        status={status}
        onKeywordChange={setKeywordInput}
        onRoleChange={setRole}
        onPlanChange={setPlan}
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
  );
}
