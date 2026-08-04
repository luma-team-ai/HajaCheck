import {
  DEFECT_GRADE_LABEL,
  DEFECT_STATUS_LABEL,
  DEFECT_TYPE_LABEL,
  INSPECTION_STATUS_LABEL,
  INSPECTION_TYPE_LABEL,
} from '../types';
import type {
  DefectGrade,
  DefectStatus,
  DefectType,
  InspectionListFilters,
  InspectionStatus,
  InspectionType,
} from '../types';

// 하자 목록(DefectListPage) 필터를 URL 쿼리파라미터에 동기화하기 위한 변환 유틸(#1508).
// 기존엔 필터가 컴포넌트 로컬 useState에만 있어 점검 상세로 이동했다가 뒤로가기하면 페이지가
// 재마운트되며 필터·페이지가 기본값으로 리셋됐다(React Query도 리셋된 필터 키로 재조회해 이전
// 목록 대신 무필터 결과가 보임). URL을 단일 진실로 두면 브라우저가 뒤로가기 시 URL을 복원해줘서
// 별도 상태 저장소 없이 해결된다.
//
// 쿼리키는 InspectionListFilters 필드명을 그대로 쓴다 — defectApi.toInspectionListParams가 이후
// 실제 API 파라미터명(예: inspectionStatus → status)으로 다시 변환하므로 여기서 백엔드 파라미터명과
// 맞출 필요는 없다. 배열 필드는 반복 키(?defectGrade=A&defectGrade=B)로 직렬화한다.
const DEFAULT_PAGE = 0;
const DEFAULT_SIZE = 10;

function parseEnumList<T extends string>(
  searchParams: URLSearchParams,
  key: string,
  validValues: Record<T, string>,
): T[] | undefined {
  const values = searchParams
    .getAll(key)
    .filter((value): value is T => Object.prototype.hasOwnProperty.call(validValues, value));
  return values.length > 0 ? values : undefined;
}

function parseNumber(searchParams: URLSearchParams, key: string): number | undefined {
  const raw = searchParams.get(key);
  if (raw == null || raw === '') return undefined;
  const parsed = Number(raw);
  return Number.isFinite(parsed) ? parsed : undefined;
}

function parseDate(searchParams: URLSearchParams, key: string): string | undefined {
  const raw = searchParams.get(key);
  return raw && raw.trim() !== '' ? raw : undefined;
}

// URL(뒤로가기로 복원되었거나 사용자가 직접 편집했을 수 있음)을 신뢰하지 않고, 알려진 enum 값만
// 통과시킨다(page/size는 없으면 기본값으로 채운다).
export function parseInspectionListFilters(searchParams: URLSearchParams): InspectionListFilters {
  return {
    inspectionType: parseEnumList<InspectionType>(searchParams, 'inspectionType', INSPECTION_TYPE_LABEL),
    inspectionStatus: parseEnumList<InspectionStatus>(
      searchParams,
      'inspectionStatus',
      INSPECTION_STATUS_LABEL,
    ),
    inspectionDateFrom: parseDate(searchParams, 'inspectionDateFrom'),
    inspectionDateTo: parseDate(searchParams, 'inspectionDateTo'),
    roundNoMin: parseNumber(searchParams, 'roundNoMin'),
    roundNoMax: parseNumber(searchParams, 'roundNoMax'),
    defectCountMin: parseNumber(searchParams, 'defectCountMin'),
    defectCountMax: parseNumber(searchParams, 'defectCountMax'),
    facilityId: parseNumber(searchParams, 'facilityId'),
    defectType: parseEnumList<DefectType>(searchParams, 'defectType', DEFECT_TYPE_LABEL),
    defectGrade: parseEnumList<DefectGrade>(searchParams, 'defectGrade', DEFECT_GRADE_LABEL),
    defectStatus: parseEnumList<DefectStatus>(searchParams, 'defectStatus', DEFECT_STATUS_LABEL),
    page: parseNumber(searchParams, 'page') ?? DEFAULT_PAGE,
    size: parseNumber(searchParams, 'size') ?? DEFAULT_SIZE,
  };
}

export function buildInspectionListSearchParams(filters: InspectionListFilters): URLSearchParams {
  const params = new URLSearchParams();

  const appendList = (key: string, values: string[] | undefined) => {
    values?.forEach((value) => params.append(key, value));
  };

  appendList('inspectionType', filters.inspectionType);
  appendList('inspectionStatus', filters.inspectionStatus);
  if (filters.inspectionDateFrom) params.set('inspectionDateFrom', filters.inspectionDateFrom);
  if (filters.inspectionDateTo) params.set('inspectionDateTo', filters.inspectionDateTo);
  if (filters.roundNoMin != null) params.set('roundNoMin', String(filters.roundNoMin));
  if (filters.roundNoMax != null) params.set('roundNoMax', String(filters.roundNoMax));
  if (filters.defectCountMin != null) params.set('defectCountMin', String(filters.defectCountMin));
  if (filters.defectCountMax != null) params.set('defectCountMax', String(filters.defectCountMax));
  if (filters.facilityId != null) params.set('facilityId', String(filters.facilityId));
  appendList('defectType', filters.defectType);
  appendList('defectGrade', filters.defectGrade);
  appendList('defectStatus', filters.defectStatus);
  // page/size는 기본값이면 생략해 URL을 간결하게 유지한다.
  if (filters.page && filters.page !== DEFAULT_PAGE) params.set('page', String(filters.page));
  if (filters.size && filters.size !== DEFAULT_SIZE) params.set('size', String(filters.size));

  return params;
}
