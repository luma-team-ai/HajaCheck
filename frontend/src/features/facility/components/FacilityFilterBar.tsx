import type { ChangeEvent } from 'react';
import { FACILITY_INITIAL_GRADE_OPTIONS } from '../constants';
import type { Facility, FacilityInitialGrade } from '../types';
import type { FacilityListFilters } from '../utils/filterFacilities';
import { getFacilityRegionOptions, getFacilityTypeOptions } from '../utils/filterFacilities';

type Props = {
  facilities: Facility[];
  filters: FacilityListFilters;
  onChange: (filters: FacilityListFilters) => void;
};

const GRADE_LABEL: Record<FacilityInitialGrade, string> = {
  A: 'A등급',
  B: 'B등급',
  C: 'C등급',
  D: 'D등급',
  E: 'E등급',
};

// admin/components/AdminUserFilterBar.tsx와 동일한 pill 드롭다운 시각 패턴(Figma 정합) — 다만
// feature 간 직접 import는 금지(React_코드_컨벤션.md §1)라 클래스/아이콘은 로컬로 다시 정의한다.
const SELECT_CLASS =
  'cursor-pointer appearance-none rounded-full border border-border bg-surface py-2.5 pr-8 pl-4 text-sm text-text-default focus:outline-none focus-visible:ring-1 focus-visible:ring-primary';

const SELECT_ARROW_STYLE = {
  backgroundImage:
    "url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='10' height='6' viewBox='0 0 10 6'%3E%3Cpath d='M1 1l4 4 4-4' stroke='%2371717a' stroke-width='1.5' fill='none' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\")",
  backgroundRepeat: 'no-repeat',
  backgroundPosition: 'right 14px center',
};

// 시설물 목록 검색+유형/지역/등급 필터(#810, Figma "hajaCheck Facility List") — pill 검색창 +
// 구분선 + pill 드롭다운 3개. 유형/지역 옵션은 하드코딩하지 않고 현재 로드된 facilities에서
// distinct 값으로 동적 구성한다(getFacilityTypeOptions/getFacilityRegionOptions).
export function FacilityFilterBar({ facilities, filters, onChange }: Props) {
  const typeOptions = getFacilityTypeOptions(facilities);
  const regionOptions = getFacilityRegionOptions(facilities);

  function handleSearchChange(event: ChangeEvent<HTMLInputElement>) {
    // 클라이언트 사이드 필터라 데이터가 이미 다 로드돼 있으므로 디바운스 없이 즉시 반영한다.
    onChange({ ...filters, search: event.target.value });
  }

  function handleTypeChange(event: ChangeEvent<HTMLSelectElement>) {
    onChange({ ...filters, type: event.target.value });
  }

  function handleRegionChange(event: ChangeEvent<HTMLSelectElement>) {
    onChange({ ...filters, region: event.target.value });
  }

  function handleGradeChange(event: ChangeEvent<HTMLSelectElement>) {
    onChange({ ...filters, grade: event.target.value as FacilityInitialGrade | '' });
  }

  return (
    <div className="flex flex-wrap items-center gap-3">
      <div className="relative w-64">
        <span
          className="pointer-events-none absolute top-1/2 left-4 -translate-y-1/2 text-text-muted"
          aria-hidden
        >
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden>
            <circle cx="7" cy="7" r="5" stroke="currentColor" strokeWidth="1.5" />
            <path d="M11 11l3 3" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
          </svg>
        </span>
        <input
          type="search"
          value={filters.search}
          onChange={handleSearchChange}
          placeholder="시설물명 검색"
          // FacilityFormModal의 "시설물명" 필드 label과 aria-label이 겹치면 같은 화면(모달이 열려도
          // 이 검색창은 언마운트되지 않음) 안에서 getByLabelText(/시설물명/) 쿼리가 모호해지므로
          // 접근성 이름은 살짝 다르게 지어 구분한다(표시 문구 placeholder는 Figma 그대로 유지).
          aria-label="시설물 이름 검색"
          className="w-full rounded-full border border-border bg-surface py-2.5 pr-4 pl-11 text-sm text-text-default placeholder:text-text-muted focus:outline-none focus-visible:ring-1 focus-visible:ring-primary"
        />
      </div>

      <div className="h-6 w-px bg-border" aria-hidden="true" />

      <select
        className={SELECT_CLASS}
        style={SELECT_ARROW_STYLE}
        value={filters.type}
        onChange={handleTypeChange}
        // FacilityFormModal의 "시설물 유형" 필드 label과 겹치지 않도록 접근성 이름을 구분한다
        // (검색창 aria-label과 동일한 이유 — 위 주석 참고).
        aria-label="유형 필터"
      >
        <option value="">유형</option>
        {typeOptions.map((type) => (
          <option key={type} value={type}>
            {type}
          </option>
        ))}
      </select>

      <select
        className={SELECT_CLASS}
        style={SELECT_ARROW_STYLE}
        value={filters.region}
        onChange={handleRegionChange}
        aria-label="시설물 지역 필터"
      >
        <option value="">지역</option>
        {regionOptions.map((region) => (
          <option key={region} value={region}>
            {region}
          </option>
        ))}
      </select>

      <select
        className={SELECT_CLASS}
        style={SELECT_ARROW_STYLE}
        value={filters.grade}
        onChange={handleGradeChange}
        aria-label="초기 등급 필터"
      >
        <option value="">등급</option>
        {FACILITY_INITIAL_GRADE_OPTIONS.map((grade) => (
          <option key={grade} value={grade}>
            {GRADE_LABEL[grade]}
          </option>
        ))}
      </select>
    </div>
  );
}
