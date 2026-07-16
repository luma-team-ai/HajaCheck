import type { DefectGrade } from '../types';
import { getGradeBgClass } from '../utils/gradeDistribution';

type Props = {
  grade: DefectGrade;
};

export function GradeBadge({ grade }: Props) {
  return (
    <span
      className={`inline-flex items-center justify-center w-7 h-7 rounded-full text-white text-[13px] font-bold shrink-0 ${getGradeBgClass(grade)}`}
    >
      {grade}
    </span>
  );
}
