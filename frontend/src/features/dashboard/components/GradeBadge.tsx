import type { DefectGrade } from '../types';
import { getGradeColor } from '../utils/gradeDistribution';

type Props = {
  grade: DefectGrade;
};

export function GradeBadge({ grade }: Props) {
  return (
    <span className="grade-badge" style={{ backgroundColor: getGradeColor(grade) }}>
      {grade}
    </span>
  );
}
