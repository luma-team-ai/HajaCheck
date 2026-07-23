import type { ChangeEvent } from 'react';
import {
  PLAN_FILTER_OPTIONS,
  PLAN_LABEL,
  ROLE_FILTER_OPTIONS,
  ROLE_LABEL,
  STATUS_FILTER_OPTIONS,
  STATUS_LABEL,
} from '../constants';
import type { AdminUserPlan, AdminUserRole, AdminUserStatus } from '../types';
import { FilterIcon } from './icons/FilterIcon';
import { SearchIcon } from './icons/SearchIcon';

/** '' = 전체(필터 미적용) — 서버 params에서 제외된다 */
export type FilterValue<T> = T | '';

interface AdminUserFilterBarProps {
  keyword: string;
  role: FilterValue<AdminUserRole>;
  plan: FilterValue<AdminUserPlan>;
  status: FilterValue<AdminUserStatus>;
  onKeywordChange: (keyword: string) => void;
  onRoleChange: (role: FilterValue<AdminUserRole>) => void;
  onPlanChange: (plan: FilterValue<AdminUserPlan>) => void;
  onStatusChange: (status: FilterValue<AdminUserStatus>) => void;
  onReset: () => void;
}

const SELECT_CLASS =
  'cursor-pointer appearance-none rounded-full border border-border bg-surface py-2 pr-8 pl-4 text-[13px] text-text-default focus:outline-none focus-visible:ring-1 focus-visible:ring-primary';

// 셀렉트 오른쪽 chevron — 배경 이미지로 그려 별도 아이콘 에셋 없이 Figma의 pill 드롭다운 형태를 맞춘다
const SELECT_ARROW_STYLE = {
  backgroundImage:
    "url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='10' height='6' viewBox='0 0 10 6'%3E%3Cpath d='M1 1l4 4 4-4' stroke='%2371717a' stroke-width='1.5' fill='none' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\")",
  backgroundRepeat: 'no-repeat',
  backgroundPosition: 'right 14px center',
};

// 사용자 관리 검색·필터 바 — Figma node-id 177-2017 기반, 플랫폼 관리자용으로 플랜 드롭다운을
// 역할·상태 사이에 추가(#577 후속, 사용자 지시) — 기업 관리자 화면(features/admin)은 회사 단위
// 스코프라 플랜이 항상 하나뿐이라 필터 의미가 없어 제거했지만, 여긴 전사 조회라 필요하다.
export function AdminUserFilterBar({
  keyword,
  role,
  plan,
  status,
  onKeywordChange,
  onRoleChange,
  onPlanChange,
  onStatusChange,
  onReset,
}: AdminUserFilterBarProps) {
  const hasActiveFilter = Boolean(keyword || role || plan || status);

  function handleKeywordChange(event: ChangeEvent<HTMLInputElement>) {
    onKeywordChange(event.target.value);
  }

  return (
    <div className="flex flex-wrap items-center justify-between gap-3">
      <div className="relative min-w-[280px] flex-1 sm:max-w-[320px]">
        <span
          className="pointer-events-none absolute top-1/2 left-4 -translate-y-1/2 text-text-muted"
          aria-hidden
        >
          <SearchIcon />
        </span>
        <input
          type="search"
          className="w-full rounded-full border border-border bg-surface py-2.5 pr-4 pl-11 text-sm text-text-default placeholder:text-text-muted focus:outline-none focus-visible:ring-1 focus-visible:ring-primary"
          placeholder="이름·이메일·기업명 검색"
          value={keyword}
          onChange={handleKeywordChange}
          aria-label="이름·이메일·기업명 검색"
        />
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <select
          className={SELECT_CLASS}
          style={SELECT_ARROW_STYLE}
          value={role}
          onChange={(event) => onRoleChange(event.target.value as FilterValue<AdminUserRole>)}
          aria-label="역할 필터"
        >
          <option value="">역할</option>
          {ROLE_FILTER_OPTIONS.map((option) => (
            <option key={option} value={option}>
              {ROLE_LABEL[option]}
            </option>
          ))}
        </select>

        <select
          className={SELECT_CLASS}
          style={SELECT_ARROW_STYLE}
          value={plan}
          onChange={(event) => onPlanChange(event.target.value as FilterValue<AdminUserPlan>)}
          aria-label="플랜 필터"
        >
          <option value="">플랜</option>
          {PLAN_FILTER_OPTIONS.map((option) => (
            <option key={option} value={option}>
              {PLAN_LABEL[option]}
            </option>
          ))}
        </select>

        <select
          className={SELECT_CLASS}
          style={SELECT_ARROW_STYLE}
          value={status}
          onChange={(event) => onStatusChange(event.target.value as FilterValue<AdminUserStatus>)}
          aria-label="상태 필터"
        >
          <option value="">상태</option>
          {STATUS_FILTER_OPTIONS.map((option) => (
            <option key={option} value={option}>
              {STATUS_LABEL[option]}
            </option>
          ))}
        </select>

        <button
          type="button"
          className="inline-flex h-9 w-9 cursor-pointer items-center justify-center rounded-full border-none bg-none text-text-muted hover:text-primary disabled:cursor-not-allowed disabled:opacity-40"
          onClick={onReset}
          disabled={!hasActiveFilter}
          aria-label="필터 초기화"
          title="필터 초기화"
        >
          <FilterIcon />
        </button>
      </div>
    </div>
  );
}
