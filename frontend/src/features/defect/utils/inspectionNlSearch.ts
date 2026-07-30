import type { NlSearchFilters } from '../nlSearchTypes';
import type { InspectionListFilters } from '../types';

const nonEmpty = <T>(values: T[] | null | undefined) =>
  values && values.length > 0 ? values : undefined;

const nonBlank = (value: string | null | undefined) => value?.trim() || undefined;

export function toInspectionFilters(nlFilters: NlSearchFilters): InspectionListFilters {
  const filters: InspectionListFilters = {};
  const inspectionType = nonEmpty(nlFilters.inspectionType);
  const inspectionStatus = nonEmpty(nlFilters.inspectionStatus);
  const inspectionDateFrom = nonBlank(nlFilters.inspectionDateFrom);
  const inspectionDateTo = nonBlank(nlFilters.inspectionDateTo);
  const defectType = nonEmpty(nlFilters.type);
  const defectGrade = nonEmpty(nlFilters.grade);
  const defectStatus = nonEmpty(nlFilters.status);

  if (inspectionType) filters.inspectionType = inspectionType;
  if (inspectionStatus) filters.inspectionStatus = inspectionStatus;
  if (inspectionDateFrom) filters.inspectionDateFrom = inspectionDateFrom;
  if (inspectionDateTo) filters.inspectionDateTo = inspectionDateTo;
  if (nlFilters.roundNoMin != null) filters.roundNoMin = nlFilters.roundNoMin;
  if (nlFilters.roundNoMax != null) filters.roundNoMax = nlFilters.roundNoMax;
  if (nlFilters.defectCountMin != null) filters.defectCountMin = nlFilters.defectCountMin;
  if (nlFilters.defectCountMax != null) filters.defectCountMax = nlFilters.defectCountMax;
  if (defectType) filters.defectType = defectType;
  if (defectGrade) filters.defectGrade = defectGrade;
  if (defectStatus) filters.defectStatus = defectStatus;
  return filters;
}

export function hasApplicableInspectionFilters(filters: InspectionListFilters): boolean {
  return Object.values(filters).some((value) =>
    Array.isArray(value) ? value.length > 0 : value !== undefined,
  );
}

export function describeUnsupported(
  nlFilters: NlSearchFilters,
  unsupportedTerms: string[],
): string[] {
  const messages: string[] = [];
  if (unsupportedTerms.length > 0) {
    messages.push(`다음 조건은 아직 지원하지 않아 제외했어요: ${unsupportedTerms.join(', ')}`);
  }
  if (nlFilters.confidenceMin != null) {
    messages.push(
      `신뢰도 ${Math.round(nlFilters.confidenceMin * 100)}% 이상 조건은 아직 점검 목록 필터에 적용할 수 없어 제외했어요`,
    );
  }
  return messages;
}
