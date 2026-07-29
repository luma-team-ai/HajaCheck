import { useQuery } from '@tanstack/react-query';
import { facilityApi } from '../api/facilityApi';
import type { FacilityStatusRow, InspectionCycleStatusRow, InspectionCycleType } from '../types';

export const inspectionCycleStatusListKey = ['facility', 'inspection-cycle', 'status-list'] as const;

const INSPECTION_TYPE_LABEL: Record<NonNullable<FacilityStatusRow['inspectionType']>, InspectionCycleType> = {
  REGULAR: '정기',
  DETAILED: '정밀',
  EMERGENCY: '긴급',
};

// #1136 — GET /api/facilities/status(FacilityStatusRow)를 화면 전용 InspectionCycleStatusRow로
// 매핑한다. inspectionType이 null(점검 이력 없음)이면 백엔드 기본값(Inspection.type 기본 REGULAR)과
// 동일하게 '정기'로 근사한다. lastInspectedAt/assigneeName이 null이면(담당자 미배정/점검 이력 없음)
// 표시용 플레이스홀더로 대체 — InspectionCycleStatusRow 자체 타입은 그대로 두고 이 매핑 경계에서만
// 흡수해, 이 화면의 다른 파일(Card/Table)을 건드리지 않는다.
function mapFacilityStatusRow(row: FacilityStatusRow): InspectionCycleStatusRow {
  return {
    id: row.facilityId,
    name: row.facilityName,
    type: row.inspectionType ? INSPECTION_TYPE_LABEL[row.inspectionType] : '정기',
    cycleMonths: row.inspectionCycleMonths ?? 0,
    lastInspectedAt: row.lastInspectedAt ?? '-',
    nextInspectionDueAt: row.nextInspectionDueAt ?? '',
    assigneeName: row.assigneeName ?? '미배정',
  };
}

export function useInspectionCycleStatusRows() {
  return useQuery({
    queryKey: inspectionCycleStatusListKey,
    queryFn: () => facilityApi.getStatusList().then((res) => res.data.map(mapFacilityStatusRow)),
  });
}
