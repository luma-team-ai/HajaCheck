import { FACILITY_DEFECT_GRADE_BADGE_CLASS } from '../facilityDefectColors';
import type { FacilityDefectGrade } from '../types';

type Props = {
  grade: FacilityDefectGrade | null;
  /** true면 상세 패널의 드롭다운형 배지(화살표 표시), 목록/테이블은 기본값(false)으로 단순 배지 */
  withChevron?: boolean;
};

// 하자 등급 배지 — dashboard/components/GradeBadge.tsx와 유사하지만 feature 간 직접 import
// 금지(React_코드_컨벤션.md §1)라 facility 로컬로 별도 정의한다.
export function FacilityGradeBadge({ grade, withChevron = false }: Props) {
  if (!grade) {
    return (
      <span className="inline-flex items-center justify-center rounded-full bg-[#f0f1f3] px-2.5 py-1 text-xs font-bold text-text-muted">
        -
      </span>
    );
  }

  return (
    <span
      className={`inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-xs font-bold ${FACILITY_DEFECT_GRADE_BADGE_CLASS[grade]}`}
    >
      {grade}
      {withChevron && <span aria-hidden="true">▾</span>}
    </span>
  );
}