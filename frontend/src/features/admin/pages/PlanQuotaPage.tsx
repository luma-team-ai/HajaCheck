import { useEffect, useMemo, useRef, useState } from 'react';
import { Pagination } from '../../../shared/components/Pagination/Pagination';
import { CurrentPlanCard } from '../components/CurrentPlanCard';
import { PlanChangeControl } from '../components/PlanChangeControl';
import { PlanQuotaKpiCards } from '../components/PlanQuotaKpiCards';
import { PlanQuotaTable } from '../components/PlanQuotaTable';
import { SearchIcon } from '../components/icons/SearchIcon';
import { useAdminCurrentPlan } from '../hooks/useAdminCurrentPlan';
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

  // 목록 크기는 고정값 — CurrentPlanCard와 나란한 좁은 컬럼에 표가 들어가는 레이아웃이라
  // 페이지 크기를 사용자가 바꿀 수 있게 하면(선택형 UI) 폭·행 수가 흔들려 오히려 어색하다.
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
  // 하향 예약(#1105 / HAJA-526, #1191) 배너·"즉시/예약" 선택에 쓰는 currentPeriodEnd·scheduledChange는
  // GET /api/admin/plan에서만 내려온다 — plan-quota 목록의 companyPlan(#508)과는 별개 조회다.
  const { data: currentPlanData } = useAdminCurrentPlan();

  const users = data?.content ?? [];
  const totalElements = data?.totalElements ?? 0;
  const totalPages = Math.max(1, Math.ceil(totalElements / pageSize));
  // "현재 플랜" 카드는 표 행 선택과 무관하게 내 회사(company_id) 플랜 고정값이다(#508 확정).
  // 조회 전에는 undefined(로딩 표시), 조회 실패 시에는 null(안내 문구)로 넘긴다.
  const companyPlan = isError ? null : data?.stats.companyPlan;
  // (#887) "활성 플랜 없음"은 정상 응답(200, companyPlan=null)이라 isError와 다른 안내를 보여준다 —
  // 회사 스코프 상속 자체는 실패하지 않고(방어 처리), 그저 회사가 아직 구독 중인 플랜이 없는 상태다.
  const hasNoActivePlan = !isLoading && !isError && data !== undefined && data.stats.companyPlan === null;

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

        {hasNoActivePlan && (
          <div
            role="status"
            className="rounded-2xl border border-dashed border-border bg-surface-muted px-4 py-3 text-sm text-text-muted"
          >
            현재 회사에 활성화된 플랜 구독이 없습니다. 플랜을 등록하면 멤버별 쿼터 사용량이 표시됩니다.
          </div>
        )}

        {/* 본문 — 쿼터 사용량 표(좌) / 현재 플랜 카드(우) */}
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-[2fr_1fr]">
          <div className="overflow-hidden rounded-[20px] border border-border bg-surface">
            <PlanQuotaTable
              users={users}
              isLoading={isLoading}
              isError={isError}
              onRetry={() => void refetch()}
            />
          </div>
          <div className="flex flex-col gap-3">
            <p className="px-4 py-3 text-xs font-medium text-text-muted">현재 플랜</p>
            <CurrentPlanCard
              plan={companyPlan}
              catalog={catalogData?.plans}
              scheduledChange={currentPlanData?.scheduledChange ?? null}
            />
            <PlanChangeControl
              currentPlan={companyPlan}
              catalog={catalogData?.plans}
              currentPeriodEnd={currentPlanData?.currentPeriodEnd ?? null}
              hasPendingSchedule={currentPlanData?.scheduledChange != null}
            />
          </div>
        </div>

        {/* 페이지네이션 — 표시 범위(좌) / 페이지 버튼(우). 표 컬럼 안이 아니라 본문 전체 하단에
            고정해, 표 행 수가 적어도 다른 화면들처럼 항상 카드 맨 아래에 위치한다. */}
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
