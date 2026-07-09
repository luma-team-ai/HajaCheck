import type { Defect, DefectGrade } from '../types';

/**
 * 하자 목록을 confidence와 grade로 필터링합니다.
 * @param defects - 필터링할 하자 목록
 * @param confidenceThreshold - confidence 최소값 (0 <= threshold <= 1)
 * @param gradeFilter - 포함할 등급 배열
 * @returns 필터링된 하자 목록
 */
export function filterDefects(
  defects: Defect[],
  confidenceThreshold: number,
  gradeFilter: DefectGrade[],
): Defect[] {
  return defects.filter(
    (defect) => defect.confidence >= confidenceThreshold && gradeFilter.includes(defect.grade),
  );
}
