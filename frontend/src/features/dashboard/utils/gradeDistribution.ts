import type { DefectGrade, GradeDistributionItem } from '../types';

const GRADE_ORDER: DefectGrade[] = ['A', 'B', 'C', 'D', 'E'];

// 등급별 색상 — A(양호,초록) → E(중대,빨강) 그라데이션 (docs 시안 기준, 하드코딩 대신 단일 지점에서 관리)
const GRADE_COLOR: Record<DefectGrade, string> = {
  A: '#16a34a',
  B: '#65a30d',
  C: '#eab308',
  D: '#f97316',
  E: '#dc2626',
};

/**
 * 하자 등급 분포를 A→E 고정 순서로 정렬합니다.
 * @param items - 정렬할 등급 분포 목록
 * @returns A→E 순서로 정렬된 새 배열
 */
export function sortGradeDistribution(items: GradeDistributionItem[]): GradeDistributionItem[] {
  return [...items].sort((a, b) => GRADE_ORDER.indexOf(a.grade) - GRADE_ORDER.indexOf(b.grade));
}

/**
 * 등급 분포 퍼센트의 합계를 계산합니다. (100에 가까운지 검증 용도)
 * @param items - 등급 분포 목록
 * @returns 퍼센트 합계
 */
export function sumGradePercent(items: GradeDistributionItem[]): number {
  return items.reduce((sum, item) => sum + item.percent, 0);
}

// 합계 검증 허용 오차(부동소수 연산 오차 흡수) — 스토리보드 DASH-01 V2
export const GRADE_TOTAL_TOLERANCE = 0.5;

/**
 * 등급 분포 퍼센트 합계가 100%에 부합하는지 검증합니다. (스토리보드 DASH-01 V2)
 * 빈 배열은 검증 대상 없음으로 true. 부동소수 오차는 tolerance 이내 허용.
 * @param items - 등급 분포 목록
 * @param tolerance - 허용 오차(기본 0.5)
 * @returns 합계가 100%로 유효하면 true
 */
export function isGradeTotalValid(
  items: GradeDistributionItem[],
  tolerance: number = GRADE_TOTAL_TOLERANCE,
): boolean {
  if (items.length === 0) return true;
  return Math.abs(sumGradePercent(items) - 100) < tolerance;
}

/**
 * 등급에 대응하는 표시 색상을 반환합니다.
 * @param grade - 하자 등급
 * @returns 색상 hex 값
 */
export function getGradeColor(grade: DefectGrade): string {
  return GRADE_COLOR[grade];
}
