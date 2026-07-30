import type { ChangeEvent } from 'react';
import { useInspectionFacilityOptions } from '../hooks/useInspectionFacilityOptions';
import { InspectionNlSearchBar } from './InspectionNlSearchBar';
import { InspectionAppliedFilters } from './InspectionAppliedFilters';
import type { InspectionFilterAxis } from './InspectionAppliedFilters';
import { INSPECTION_STATUS_LABEL } from '../types';
import type { InspectionListFilters, InspectionStatus } from '../types';

type Props = {
  filters: InspectionListFilters;
  onChange: (filters: InspectionListFilters) => void;
  onNlApplied?: () => void;
};

// 점검 목록(HAJA-393/394, #725/#726) 필터.
//
// 2026-07-26 정정(#878/HAJA-452, 백엔드 PR #891): GET /api/inspections가 defectType/defectGrade/
// defectStatus 배열 파라미터(EXISTS 서브쿼리)를 지원하게 되어, 점검 단위도 자연어(하자조건) 검색
// 대상이다 — 기존 "점검 단위는 AI 검색 대상이 아니다" 주석은 더 이상 유효하지 않다. 시각 톤은
// DefectFilterBar와 동일한 클래스(defect-filter-bar*)를 그대로 재사용해 화면 스타일을 통일한다
// (사용자 확정 지시 — 시각 디자인은 유지, 컬럼/필터 대상만 점검 단위로 재해석).
export function InspectionFilterBar({ filters, onChange, onNlApplied }: Props) {
  const { data: facilityOptions } = useInspectionFacilityOptions();

  function handleStatusChange(event: ChangeEvent<HTMLSelectElement>) {
    const value = event.target.value as InspectionStatus | '';
    onChange({
      ...filters,
      inspectionStatus: value === '' ? undefined : [value],
      page: 0,
    });
  }

  function handleFacilityChange(event: ChangeEvent<HTMLSelectElement>) {
    const value = event.target.value;
    onChange({ ...filters, facilityId: value === '' ? undefined : Number(value), page: 0 });
  }

  function handleNlApply(nextFilters: InspectionListFilters) {
    onChange({ ...nextFilters, page: 0, size: filters.size });
    onNlApplied?.();
  }

  function handleRemoveFilter(axis: InspectionFilterAxis) {
    const next = { ...filters, page: 0 };
    if (axis === 'inspectionDate') {
      next.inspectionDateFrom = undefined;
      next.inspectionDateTo = undefined;
    } else if (axis === 'roundNo') {
      next.roundNoMin = undefined;
      next.roundNoMax = undefined;
    } else if (axis === 'defectCount') {
      next.defectCountMin = undefined;
      next.defectCountMax = undefined;
    } else {
      next[axis] = undefined;
    }
    onChange(next);
  }

  function handleReset() {
    onChange({ page: 0, size: filters.size });
  }

  return (
    <section className="defect-filter-bar" aria-label="점검 목록 검색 및 필터">
      <InspectionNlSearchBar onApply={handleNlApply} />

      <div className="defect-filter-bar__manual" aria-label="점검 상세 필터">
        <select
          className="defect-filter-bar__select"
          aria-label="점검 상태 필터"
          value={
            filters.inspectionStatus?.length === 1
              ? filters.inspectionStatus[0]
              : filters.inspectionStatus && filters.inspectionStatus.length > 1
                ? '__MULTIPLE__'
                : ''
          }
          onChange={handleStatusChange}
        >
          <option value="">전체 상태</option>
          {filters.inspectionStatus && filters.inspectionStatus.length > 1 && (
            <option value="__MULTIPLE__" disabled>
              {filters.inspectionStatus.length}개 상태 적용 중
            </option>
          )}
          {(Object.entries(INSPECTION_STATUS_LABEL) as [InspectionStatus, string][]).map(
            ([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ),
          )}
        </select>

        <select
          className="defect-filter-bar__select"
          aria-label="시설물 필터"
          value={filters.facilityId ?? ''}
          onChange={handleFacilityChange}
        >
          <option value="">전체 시설물</option>
          {(facilityOptions ?? []).map((option) => (
            <option key={option.id} value={option.id}>
              {option.name}
            </option>
          ))}
        </select>
      </div>

      <InspectionAppliedFilters
        filters={filters}
        facilityName={facilityOptions?.find((option) => option.id === filters.facilityId)?.name}
        onRemove={handleRemoveFilter}
        onReset={handleReset}
      />
    </section>
  );
}
