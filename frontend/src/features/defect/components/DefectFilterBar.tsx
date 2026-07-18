import type { ChangeEvent } from 'react';
import { Button } from '../../../shared/components/Button';
import {
  DEFECT_GRADE_LABEL,
  DEFECT_STATUS_LABEL,
  DEFECT_TYPE_LABEL,
  type DefectGrade,
  type DefectListFilters,
  type DefectStatus,
  type DefectType,
} from '../types';

type Props = {
  filters: DefectListFilters;
  onChange: (filters: DefectListFilters) => void;
};

const SELECT_CLASSES =
  'cursor-pointer rounded-2xl border border-border bg-surface-muted px-3 py-2 text-sm text-text-default';

// PRD FR-4 §187 "유형/등급/상태별 필터·검색" — 3개 드롭다운. 값 변경 시 항상 page를 0으로 리셋해
// 이전 필터의 페이지 번호가 새 필터 결과 범위를 벗어나는 것을 방지한다.
export function DefectFilterBar({ filters, onChange }: Props) {
  function handleTypeChange(event: ChangeEvent<HTMLSelectElement>) {
    const value = event.target.value as DefectType | '';
    onChange({ ...filters, type: value === '' ? undefined : value, page: 0 });
  }

  function handleGradeChange(event: ChangeEvent<HTMLSelectElement>) {
    const value = event.target.value as DefectGrade | '';
    onChange({ ...filters, grade: value === '' ? undefined : value, page: 0 });
  }

  function handleStatusChange(event: ChangeEvent<HTMLSelectElement>) {
    const value = event.target.value as DefectStatus | '';
    onChange({ ...filters, status: value === '' ? undefined : value, page: 0 });
  }

  function handleReset() {
    onChange({ page: 0, size: filters.size });
  }

  return (
    <div className="flex flex-wrap items-center gap-3">
      <select
        className={SELECT_CLASSES}
        aria-label="유형 필터"
        value={filters.type ?? ''}
        onChange={handleTypeChange}
      >
        <option value="">전체 유형</option>
        {(Object.entries(DEFECT_TYPE_LABEL) as [DefectType, string][]).map(([value, label]) => (
          <option key={value} value={value}>
            {label}
          </option>
        ))}
      </select>

      <select
        className={SELECT_CLASSES}
        aria-label="등급 필터"
        value={filters.grade ?? ''}
        onChange={handleGradeChange}
      >
        <option value="">전체 등급</option>
        {(Object.entries(DEFECT_GRADE_LABEL) as [DefectGrade, string][]).map(([value, label]) => (
          <option key={value} value={value}>
            {value} · {label}
          </option>
        ))}
      </select>

      <select
        className={SELECT_CLASSES}
        aria-label="상태 필터"
        value={filters.status ?? ''}
        onChange={handleStatusChange}
      >
        <option value="">전체 상태</option>
        {(Object.entries(DEFECT_STATUS_LABEL) as [DefectStatus, string][]).map(([value, label]) => (
          <option key={value} value={value}>
            {label}
          </option>
        ))}
      </select>

      <Button variant="secondary" size="sm" onClick={handleReset}>
        필터 초기화
      </Button>
    </div>
  );
}
