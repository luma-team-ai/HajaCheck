import { DASHBOARD_COLOR_CLASS } from '../colors';
import type { InspectionStatus } from '../types';

// 상태 → 배지 Tailwind 유틸리티 클래스 매핑. 토큰에 없는 값이라 임의값 문법 사용.
// Record<InspectionStatus, ...>로 신규 상태 추가 시 컴파일 타임 누락 방지.
// 색상 리터럴은 colors.ts의 statusBadge* 상수로 단일 관리(완전한 클래스명 유지 —
// 동적 조합 `bg-${color}-50` 금지 규칙 준수, React_코드_컨벤션.md §8 / Tailwind 전환 지침).
const STATUS_BADGE_CLASS: Record<InspectionStatus, string> = {
  분석중: DASHBOARD_COLOR_CLASS.statusBadgeBlue,
  검수대기: DASHBOARD_COLOR_CLASS.statusBadgeOrange,
  조치대기: DASHBOARD_COLOR_CLASS.statusBadgeOrange,
  완료: DASHBOARD_COLOR_CLASS.statusBadgeGreen,
};

/**
 * 점검 상태에 대응하는 배지 배경/텍스트 색상 Tailwind 클래스를 반환합니다.
 * @param status - 점검 상태
 * @returns 완전한 Tailwind 클래스명 조합(예: 'bg-[#e6ecff] text-[#3452e0]')
 */
export function getInspectionStatusClass(status: InspectionStatus): string {
  return STATUS_BADGE_CLASS[status];
}
