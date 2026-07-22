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

// 관리자 > 플랜·쿼터 관리 — Figma node-id 1197-3519 "hajaCheck Business Admin - 플랜·쿼터 관리 워크스페이스".
// 헤더(브레드크럼)·사이드바는 AppShellRoute → AppLayout이 담당하므로 이 페이지는 CONTENT 영역만 그린다.
// 실제 인가는 백엔드 책임이고, 라우트의 AdminRoute는 잘못된 화면을 감추기 위한 UX 가드일 뿐이다.
//
// 스코프(2026-07-21 확정): 시안의 여러 회사명은 임의 목업이고, 실제로는 로그인한 관리자
// 소속 회사 하나로 한정된다 — 여기 표는 "내 회사"에 등록된 멤버별 쿼터 사용량이다(다른 회사 조회 아님).
export function PlanQuotaPage() {
  const [keywordInput, setKeywordInput] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);

  const pageSize = PLAN_QUOTA_DEFAULT_PAGE_SIZE;

  // 타이핑마다 조회하지 않도록 검색어를 디바운스한다
  useEffect(() => {
    const timer = setTimeout(() => setKeyword(keywordInput), KEYWORD_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [keywordInput]);

  // 검색어가 바뀌면 1페이지로 되돌린다 — 렌더 중 동기 조정(AdminUsersPage와 동일 패턴, 한 프레임 깜빡임 방지)
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
  // "현재 플랜" 카드는 표 행 선택과 무관하게 내 회사(company_id) 플랜 고정값이다(#508 확정).
  // 조회 전에는 undefined(로딩 표시), 조회 실패 시에는 null(안내 문구)로 넘긴다.
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
              우리 회사 소속 멤버들의 플랜 상태와 쿼터 사용량을 모니터링하고 관리합니다.
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
