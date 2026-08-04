import type { Defect, DefectGrade } from '../types';

/**
 * 하자 목록을 confidence와 grade로 필터링합니다.
 *
 * 등급 미판정(grade=null) 하자는 **등급 필터를 통과시킨다** — A~E 어디에도 속하지 않아
 * `gradeFilter.includes(null)`이 항상 false였고, 그래서 5개 등급을 전부 켜도 화면에 나타나지
 * 않았다. 그런 하자도 totalCount 분모에는 남으므로 검수를 확정할 방법이 없어 "점검 요약"이
 * 영구히 잠겼다(#1395). 신뢰도 필터는 그대로 적용한다(등급과 무관한 축).
 *
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
    (defect) =>
      defect.confidence >= confidenceThreshold &&
      (defect.grade == null || gradeFilter.includes(defect.grade)),
  );
}
