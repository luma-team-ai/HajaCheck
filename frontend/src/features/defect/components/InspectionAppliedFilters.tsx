import { Button } from '../../../shared/components/Button';
import {
  DEFECT_GRADE_LABEL,
  DEFECT_STATUS_LABEL,
  DEFECT_TYPE_LABEL,
  INSPECTION_STATUS_LABEL,
  INSPECTION_TYPE_LABEL,
} from '../types';
import type { InspectionListFilters } from '../types';

export type InspectionFilterAxis =
  | 'inspectionType'
  | 'inspectionStatus'
  | 'facilityId'
  | 'inspectionDate'
  | 'roundNo'
  | 'defectCount'
  | 'defectType'
  | 'defectGrade'
  | 'defectStatus';

type Props = {
  filters: InspectionListFilters;
  facilityName?: string;
  onRemove: (axis: InspectionFilterAxis) => void;
  onReset: () => void;
};

function formatRange(
  min: number | undefined,
  max: number | undefined,
  unit: string,
): string | null {
  if (min == null && max == null) return null;
  if (min != null && max != null) {
    return min === max ? `${min}${unit}` : `${min}~${max}${unit}`;
  }
  return min != null ? `${min}${unit} 이상` : `${max}${unit} 이하`;
}

function buildFilters(filters: InspectionListFilters, facilityName?: string) {
  const date =
    filters.inspectionDateFrom && filters.inspectionDateTo
      ? filters.inspectionDateFrom === filters.inspectionDateTo
        ? filters.inspectionDateFrom
        : `${filters.inspectionDateFrom} ~ ${filters.inspectionDateTo}`
      : filters.inspectionDateFrom
        ? `${filters.inspectionDateFrom} 이후`
        : filters.inspectionDateTo
          ? `${filters.inspectionDateTo} 이전`
          : null;
  const round = formatRange(filters.roundNoMin, filters.roundNoMax, '회차');
  const defectCount = formatRange(filters.defectCountMin, filters.defectCountMax, '건');

  return [
    filters.inspectionType?.length
      ? {
          key: 'inspectionType' as const,
          label: `점검유형: ${filters.inspectionType.map((value) => INSPECTION_TYPE_LABEL[value]).join(', ')}`,
        }
      : null,
    filters.inspectionStatus?.length
      ? {
          key: 'inspectionStatus' as const,
          label: `점검상태: ${filters.inspectionStatus.map((value) => INSPECTION_STATUS_LABEL[value]).join(', ')}`,
        }
      : null,
    filters.facilityId != null
      ? { key: 'facilityId' as const, label: `시설물: ${facilityName ?? filters.facilityId}` }
      : null,
    date ? { key: 'inspectionDate' as const, label: `점검일: ${date}` } : null,
    round ? { key: 'roundNo' as const, label: `점검회차: ${round}` } : null,
    defectCount
      ? { key: 'defectCount' as const, label: `전체 하자 건수: ${defectCount}` }
      : null,
    filters.defectType?.length
      ? {
          key: 'defectType' as const,
          label: `하자유형: ${filters.defectType.map((value) => DEFECT_TYPE_LABEL[value]).join(', ')}`,
        }
      : null,
    filters.defectGrade?.length
      ? {
          key: 'defectGrade' as const,
          label: `하자등급: ${filters.defectGrade.map((value) => DEFECT_GRADE_LABEL[value]).join(', ')}`,
        }
      : null,
    filters.defectStatus?.length
      ? {
          key: 'defectStatus' as const,
          label: `하자상태: ${filters.defectStatus.map((value) => DEFECT_STATUS_LABEL[value]).join(', ')}`,
        }
      : null,
  ].filter((item): item is { key: InspectionFilterAxis; label: string } => item !== null);
}

export function InspectionAppliedFilters({ filters, facilityName, onRemove, onReset }: Props) {
  const appliedFilters = buildFilters(filters, facilityName);
  if (appliedFilters.length === 0) return null;

  return (
    <div className="defect-filter-bar__controls">
      <span className="defect-filter-bar__label">적용된 필터:</span>
      {appliedFilters.map((filter) => (
        <button
          type="button"
          className="defect-filter-bar__chip"
          key={filter.key}
          aria-label={`${filter.label} 필터 제거`}
          onClick={() => onRemove(filter.key)}
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
        onClick={onReset}
      >
        초기화
      </Button>
    </div>
  );
}
