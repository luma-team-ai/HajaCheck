import type { ChangeEvent } from 'react';
import { Button } from '../../../shared/components/Button';
import { useInspectionFacilityOptions } from '../hooks/useInspectionFacilityOptions';
import { InspectionNlSearchBar } from './InspectionNlSearchBar';
import {
  DEFECT_GRADE_LABEL,
  DEFECT_STATUS_LABEL,
  DEFECT_TYPE_LABEL,
  INSPECTION_STATUS_LABEL,
} from '../types';
import type { InspectionListFilters, InspectionStatus } from '../types';

type Props = {
  filters: InspectionListFilters;
  onChange: (filters: InspectionListFilters) => void;
};

type AppliedFilterKey = 'status' | 'facilityId' | 'defectType' | 'defectGrade' | 'defectStatus';

// 점검 목록(HAJA-393/394, #725/#726) 필터.
//
// 2026-07-26 정정(#878/HAJA-452, 백엔드 PR #891): GET /api/inspections가 defectType/defectGrade/
// defectStatus 배열 파라미터(EXISTS 서브쿼리)를 지원하게 되어, 점검 단위도 자연어(하자조건) 검색
// 대상이다 — 기존 "점검 단위는 AI 검색 대상이 아니다" 주석은 더 이상 유효하지 않다. 시각 톤은
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

  function handleNlApply(patch: Partial<InspectionListFilters>) {
    onChange({ ...filters, ...patch, page: 0 });
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
    filters.defectType && filters.defectType.length > 0
      ? {
          key: 'defectType',
          label: `하자유형: ${filters.defectType.map((value) => DEFECT_TYPE_LABEL[value]).join(', ')}`,
        }
      : null,
    filters.defectGrade && filters.defectGrade.length > 0
      ? {
          key: 'defectGrade',
          label: `하자등급: ${filters.defectGrade.map((value) => DEFECT_GRADE_LABEL[value]).join(', ')}`,
        }
      : null,
    filters.defectStatus && filters.defectStatus.length > 0
      ? {
          key: 'defectStatus',
          label: `하자상태: ${filters.defectStatus.map((value) => DEFECT_STATUS_LABEL[value]).join(', ')}`,
        }
      : null,
  ].filter((filter): filter is { key: AppliedFilterKey; label: string } => filter !== null);

  return (
    <section className="defect-filter-bar" aria-label="점검 목록 검색 및 필터">
      <InspectionNlSearchBar onApply={handleNlApply} />

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
