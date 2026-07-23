import { useEffect, useMemo, useRef, useState } from 'react';
import { Pagination } from '../../../shared/components/Pagination/Pagination';
import { CurrentPlanCard } from '../components/CurrentPlanCard';
import { PlanQuotaKpiCards } from '../components/PlanQuotaKpiCards';
import { PlanQuotaTable } from '../components/PlanQuotaTable';
import { SearchIcon } from '../components/icons/SearchIcon';
import { useAdminPlanCatalog } from '../hooks/useAdminPlanCatalog';
import { usePlanQuotaUsers } from '../hooks/usePlanQuotaUsers';
import { PLAN_QUOTA_DEFAULT_PAGE_SIZE } from '../planQuota.constants';

const KEYWORD_DEBOUNCE_MS = 300;

// 플랫폼 관리자 > 플랜·쿼터 관리(#625) — features/admin/pages/PlanQuotaPage.tsx(Figma node-id
// 1197-3519, #508)를 그대로 옮긴 것. 기업 관리자 화면은 요청 관리자 회사로 스코프되지만, 이 화면은
// PLATFORM_ADMIN 전용 GET /api/platform-admin/plans-quota, /api/platform-admin/plans(회사 스코프
// 없음)를 호출한다(백엔드 신규 엔드포인트는 backend/624-platform-admin-plan-quota 워크트리에서 별도 구현).
// 헤더(브레드크럼)·사이드바는 PlatformAdminShellRoute가 담당하므로 이 페이지는 CONTENT 영역만 그린다.
export function PlatformAdminPlanQuotaPage() {
  const [keywordInput, setKeywordInput] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);

  const pageSize = PLAN_QUOTA_DEFAULT_PAGE_SIZE;

  // 타이핑마다 조회하지 않도록 검색어를 디바운스한다
  useEffect(() => {
    const timer = setTimeout(() => setKeyword(keywordInput), KEYWORD_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [keywordInput]);

  // 검색어가 바뀌면 1페이지로 되돌린다 — 렌더 중 동기 조정(원본과 동일 패턴, 한 프레임 깜빡임 방지)
  const prevKeywordRef = useRef(keyword);
  if (prevKeywordRef.current !== keyword) {
    prevKeywordRef.current = keyword;
    setPage(1);
  }

  const params = useMemo(
    () => ({ page, size: pageSize, ...(keyword ? { keyword } : {}) }),
    [page, pageSize, keyword],
  );

  const { data, isLoading, isError, refetch } = usePlanQuotaUsers(params);
  const { data: catalogData } = useAdminPlanCatalog();

  const users = data?.content ?? [];
  const totalElements = data?.totalElements ?? 0;
  const totalPages = Math.max(1, Math.ceil(totalElements / pageSize));
  const companyPlan = isError ? null : data?.stats.companyPlan;

  const rangeStart = totalElements === 0 ? 0 : (page - 1) * pageSize + 1;
  const rangeEnd = Math.min(page * pageSize, totalElements);

  return (
    <div className="flex min-h-full flex-col bg-surface-muted p-6 sm:p-8">
      <div className="flex flex-col gap-6 rounded-[20px] border border-border bg-surface p-6 sm:p-8">
        {/* 헤더 — 제목·설명(좌) / 검색(우) */}
        <div className="flex flex-wrap items-start justify-between gap-4 border-b border-border pb-6">
          <div>
            <h1 className="m-0 text-2xl font-bold text-heading">사용자 플랜·쿼터 관리</h1>
            <p className="mt-2 max-w-md text-sm text-text-muted">
              전사 사용자들의 플랜 상태와 쿼터 사용량을 모니터링하고 관리합니다.
            </p>
          </div>
          <div className="relative w-full sm:w-64">
            <span
              className="pointer-events-none absolute top-1/2 left-3.5 -translate-y-1/2 text-text-muted"
              aria-hidden
            >
              <SearchIcon />
            </span>
            <input
              type="search"
              className="w-full rounded-full border border-border bg-surface py-2.5 pr-4 pl-11 text-sm text-text-default placeholder:text-text-muted focus:outline-none focus-visible:ring-1 focus-visible:ring-primary"
              placeholder="사용자 검색..."
              value={keywordInput}
              onChange={(event) => setKeywordInput(event.target.value)}
              aria-label="사용자 검색"
            />
          </div>
        </div>

        <PlanQuotaKpiCards stats={data?.stats} isError={isError} />

        {/* 본문 — 쿼터 사용량 표(좌) / 현재 플랜 카드(우) */}
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-[2fr_1fr]">
          <PlanQuotaTable
            users={users}
            isLoading={isLoading}
            isError={isError}
            onRetry={() => void refetch()}
          />
          <div className="flex flex-col gap-3">
            <p className="px-4 py-3 text-xs font-medium text-text-muted">현재 플랜</p>
            <CurrentPlanCard plan={companyPlan} catalog={catalogData?.plans} />
          </div>
        </div>

        {/* 페이지네이션 — 표시 범위(좌) / 페이지 버튼(우) */}
        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-border pt-6">
          <p className="text-sm text-text-muted">
            전체 {totalElements.toLocaleString('ko-KR')}명 중 {rangeStart}-{rangeEnd} 표시
          </p>
          <Pagination currentPage={page} totalPages={totalPages} onPageChange={setPage} />
        </div>
      </div>
    </div>
  );
}
