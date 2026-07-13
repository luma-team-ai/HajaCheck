import type { InspectionStatus } from '../types';

export type StatusBadgeVariant = 'blue' | 'orange' | 'green';

// 상태 → 배지 색상 매핑 (Record<InspectionStatus, ...>로 신규 상태 추가 시 컴파일 타임 누락 방지)
const STATUS_BADGE_VARIANT: Record<InspectionStatus, StatusBadgeVariant> = {
  분석중: 'blue',
  검수대기: 'orange',
  조치대기: 'orange',
  완료: 'green',
};

/**
 * 점검 상태에 대응하는 배지 색상 variant를 반환합니다.
 * @param status - 점검 상태
 * @returns 배지 색상 variant
 */
export function getInspectionStatusVariant(status: InspectionStatus): StatusBadgeVariant {
  return STATUS_BADGE_VARIANT[status];
}
