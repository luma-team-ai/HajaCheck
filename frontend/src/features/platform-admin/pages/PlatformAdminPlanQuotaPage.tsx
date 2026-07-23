import { useEffect, useMemo, useRef, useState } from 'react';
import { Pagination } from '../../../shared/components/Pagination/Pagination';
import { PLAN_FILTER_OPTIONS } from '../constants';
import { PlanPolicyModal } from '../components/PlanPolicyModal';
import { PlanQuotaKpiCards } from '../components/PlanQuotaKpiCards';
import { PlanQuotaTable } from '../components/PlanQuotaTable';
import { FilterIcon } from '../components/icons/FilterIcon';
import { SearchIcon } from '../components/icons/SearchIcon';
import { usePlanPolicies } from '../hooks/usePlanPolicies';
import { usePlanQuotaUsers } from '../hooks/usePlanQuotaUsers';
import { useUpdatePlanPolicies } from '../hooks/useUpdatePlanPolicies';
import { fromPlanPolicyForm, toPlanPolicyForm } from '../planPolicy.mapper';
import { PLAN_POLICY_DEFAULTS } from '../planPolicy.constants';
import { PLAN_LABEL, PLAN_QUOTA_DEFAULT_PAGE_SIZE } from '../planQuota.constants';
import type { AdminUserPlan } from '../types';

const KEYWORD_DEBOUNCE_MS = 300;
/** '' = 전체(필터 미적용) — 서버 params에서 제외된다(AdminUserFilterBar와 동일 계약) */
type PlanFilterValue = AdminUserPlan | '';

// 플랫폼 관리자 > 플랜·쿼터 관리(#625) — Figma node-id 1206-2639(플랫폼 관리자 기준 화면)를 따른다.
// 기업 관리자 화면(features/admin/pages/PlanQuotaPage.tsx, #508)은 요청 관리자 회사로 스코프되고
// 사이드에 고정된 "현재 플랜" 카드를 두지만, 이 화면은 전사 스코프라 사용자별 플랜·남은 기간·상태를
// 표 하나에 담는다(사이드 카드 없음). PLATFORM_ADMIN 전용 GET /api/platform-admin/plans-quota(회사
// 스코프 없음)를 호출한다(백엔드 신규 엔드포인트는 backend/624-platform-admin-plan-quota 워크트리에서
// 별도 구현). 헤더(브레드크럼)·사이드바는 PlatformAdminShellRoute가 담당하므로 이 페이지는 CONTENT
// 영역만 그린다.
export function PlatformAdminPlanQuotaPage() {
  const [keywordInput, setKeywordInput] = useState('');
  const [keyword, setKeyword] = useState('');
  const [plan, setPlan] = useState<PlanFilterValue>('');
  const [page, setPage] = useState(1);
  const [isPolicyModalOpen, setPolicyModalOpen] = useState(false);
  // plans 테이블에서 실제 값을 가져온다(#624 후속, 사용자 지시) — 조회 실패/로딩 중에는 기본값으로
  // 자리를 지킨다(모달을 열 수는 있되 값이 실제 정책과 다를 수 있음을 감수 — 조회 실패는 드묾).
  const { data: planPolicies } = usePlanPolicies();
  const { updatePolicies, isPending: isSavingPolicy, error: savePolicyError, resetError: resetSavePolicyError } =
    useUpdatePlanPolicies();
  const policy = planPolicies ? toPlanPolicyForm(planPolicies) : PLAN_POLICY_DEFAULTS;

  const pageSize = PLAN_QUOTA_DEFAULT_PAGE_SIZE;

  // 타이핑마다 조회하지 않도록 검색어를 디바운스한다
  useEffect(() => {
    const timer = setTimeout(() => setKeyword(keywordInput), KEYWORD_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [keywordInput]);

  // 검색어·플랜 필터가 바뀌면 1페이지로 되돌린다 — 렌더 중 동기 조정(원본과 동일 패턴, 한 프레임 깜빡임 방지)
  const filterSignature = `${keyword}|${plan}`;
  const prevFilterSignatureRef = useRef(filterSignature);
  if (prevFilterSignatureRef.current !== filterSignature) {
    prevFilterSignatureRef.current = filterSignature;
    setPage(1);
  }

  const params = useMemo(
    () => ({ page, size: pageSize, ...(keyword ? { keyword } : {}), ...(plan ? { plan } : {}) }),
    [page, pageSize, keyword, plan],
  );

  const { data, isLoading, isError, refetch } = usePlanQuotaUsers(params);

  const users = data?.content ?? [];
  const totalElements = data?.totalElements ?? 0;
  const totalPages = Math.max(1, Math.ceil(totalElements / pageSize));

  const rangeStart = totalElements === 0 ? 0 : (page - 1) * pageSize + 1;
  const rangeEnd = Math.min(page * pageSize, totalElements);

  return (
    <div className="flex min-h-full flex-col bg-surface-muted p-6 sm:p-8">
      <div className="flex flex-col gap-6 rounded-[20px] border border-border bg-surface p-6 sm:p-8">
        {/* 헤더 — 제목·설명(좌) / 검색·정책 설정(우) */}
        <div className="flex flex-wrap items-start justify-between gap-4 border-b border-border pb-6">
          <div>
            <h1 className="m-0 text-2xl font-bold text-heading">사용자 플랜·쿼터 관리</h1>
            <p className="mt-2 max-w-md text-sm text-text-muted">
              전사 사용자들의 플랜 상태와 쿼터 사용량을 모니터링하고 관리합니다.
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-3">
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
                placeholder="사용자 또는 기업 검색..."
                value={keywordInput}
                onChange={(event) => setKeywordInput(event.target.value)}
                aria-label="사용자 또는 기업 검색"
              />
            </div>
            <select
              className="cursor-pointer appearance-none rounded-full border border-border bg-surface py-2.5 pr-8 pl-4 text-sm text-text-default focus:outline-none focus-visible:ring-1 focus-visible:ring-primary"
              style={{
                backgroundImage:
                  "url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='10' height='6' viewBox='0 0 10 6'%3E%3Cpath d='M1 1l4 4 4-4' stroke='%2371717a' stroke-width='1.5' fill='none' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\")",
                backgroundRepeat: 'no-repeat',
                backgroundPosition: 'right 12px center',
              }}
              value={plan}
              onChange={(event) => setPlan(event.target.value as PlanFilterValue)}
              aria-label="플랜 필터"
            >
              <option value="">플랜 전체</option>
              {PLAN_FILTER_OPTIONS.map((option) => (
                <option key={option} value={option}>
                  {PLAN_LABEL[option]}
                </option>
              ))}
            </select>
            <button
              type="button"
              className="flex items-center gap-2 whitespace-nowrap rounded-full border border-border bg-surface px-4 py-2.5 text-sm font-semibold text-text-default hover:border-primary hover:text-primary"
              onClick={() => setPolicyModalOpen(true)}
            >
              <FilterIcon />
              플랜 정책 설정
            </button>
          </div>
        </div>

        <PlanQuotaKpiCards stats={data?.stats} isError={isError} />

        <PlanQuotaTable
          users={users}
          isLoading={isLoading}
          isError={isError}
          onRetry={() => void refetch()}
        />

        {/* 페이지네이션 — 표시 범위(좌) / 페이지 버튼(우) */}
        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-border pt-6">
          <p className="text-sm text-text-muted">
            전체 {totalElements.toLocaleString('ko-KR')}명 중 {rangeStart}-{rangeEnd} 표시
          </p>
          <Pagination currentPage={page} totalPages={totalPages} onPageChange={setPage} />
        </div>
      </div>

      <PlanPolicyModal
        open={isPolicyModalOpen}
        onClose={() => {
          setPolicyModalOpen(false);
          resetSavePolicyError();
        }}
        initialValues={policy}
        onSave={(values) => updatePolicies(fromPlanPolicyForm(values)).then(() => {})}
        isSubmitting={isSavingPolicy}
        submitErrorMessage={savePolicyError?.message}
      />
    </div>
  );
}
