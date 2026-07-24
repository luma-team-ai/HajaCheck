import type { ChangeEvent } from 'react';
import { Button } from '../../../shared/components/Button';
import { useInspectionFacilityOptions } from '../hooks/useInspectionFacilityOptions';
import { INSPECTION_STATUS_LABEL } from '../types';
import type { InspectionListFilters, InspectionStatus } from '../types';

type Props = {
  filters: InspectionListFilters;
  onChange: (filters: InspectionListFilters) => void;
};

type AppliedFilterKey = 'status' | 'facilityId';

// 점검 목록(HAJA-393/394, #725/#726) 필터 — 하자 목록의 DefectFilterBar(AI 자연어 검색 포함)와
// 달리 점검 단위는 AI 검색 대상이 아니다(contract.md — nl-search는 하자 필터 전용 API). 시각 톤은
// DefectFilterBar와 동일한 클래스(defect-filter-bar*)를 그대로 재사용해 화면 스타일을 통일한다
// (사용자 확정 지시 — 시각 디자인은 유지, 컬럼/필터 대상만 점검 단위로 재해석).
export function InspectionFilterBar({ filters, onChange }: Props) {
  const { data: facilityOptions } = useInspectionFacilityOptions();

  function handleStatusChange(event: ChangeEvent<HTMLSelectElement>) {
    const value = event.target.value as InspectionStatus | '';
    onChange({ ...filters, status: value === '' ? undefined : value, page: 0 });
  }

  function handleFacilityChange(event: ChangeEvent<HTMLSelectElement>) {
    const value = event.target.value;
    onChange({ ...filters, facilityId: value === '' ? undefined : Number(value), page: 0 });
  }

  function handleRemoveFilter(key: AppliedFilterKey) {
    onChange({ ...filters, [key]: undefined, page: 0 });
  }

  function handleReset() {
    onChange({ page: 0, size: filters.size });
  }

  const appliedFilters: { key: AppliedFilterKey; label: string }[] = [
    filters.status
      ? { key: 'status', label: `상태: ${INSPECTION_STATUS_LABEL[filters.status]}` }
      : null,
    filters.facilityId != null
      ? {
          key: 'facilityId',
          label: `시설물: ${
            facilityOptions?.find((option) => option.id === filters.facilityId)?.name ?? filters.facilityId
          }`,
        }
      : null,
  ].filter((filter): filter is { key: AppliedFilterKey; label: string } => filter !== null);

  return (
    <section className="defect-filter-bar" aria-label="점검 목록 검색 및 필터">
      <div className="defect-filter-bar__manual" aria-label="점검 상세 필터">
        <select
          className="defect-filter-bar__select"
          aria-label="점검 상태 필터"
          value={filters.status ?? ''}
          onChange={handleStatusChange}
        >
          <option value="">전체 상태</option>
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

      {appliedFilters.length > 0 && (
        <div className="defect-filter-bar__controls">
          <span className="defect-filter-bar__label">적용된 필터:</span>
          {appliedFilters.map((filter) => (
            <button
              type="button"
              className="defect-filter-bar__chip"
              key={filter.key}
              aria-label={`${filter.label} 필터 제거`}
              onClick={() => handleRemoveFilter(filter.key)}
            >
              <span>{filter.label}</span>
              <span aria-hidden="true">×</span>
            </button>
          ))}

          <Button
            variant="secondary"
            size="sm"
            className="defect-filter-bar__reset"
            aria-label="필터 초기화"
            onClick={handleReset}
          >
            초기화
          </Button>
        </div>
      )}
    </section>
  );
}
