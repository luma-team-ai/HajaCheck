import { DASHBOARD_COLOR_CLASS } from '../colors';
import type { DefectGrade } from '../types';
import { getGradeBgClass } from '../utils/gradeDistribution';

type Props = {
  grade: DefectGrade | null;
};

// grade가 null(BE 미분류 하자)이면 등급색 대신 중립 배지로 대체 — PendingPriorityResponse.grade 정합(HAJA-17)
export function GradeBadge({ grade }: Props) {
  const bgClass = grade === null ? DASHBOARD_COLOR_CLASS.gradeUnknownBg : getGradeBgClass(grade);

  return (
    <span
      className={`inline-flex items-center justify-center w-7 h-7 rounded-full text-white text-[13px] font-bold shrink-0 ${bgClass}`}
    >
      {grade ?? '-'}
    </span>
  );
}
