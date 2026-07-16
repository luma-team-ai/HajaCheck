// 등급 배지 — 목록 패널/선택 팝업 공용. 색상은 GRADE_COLOR/GRADE_LABEL에서만 가져온다(하드코딩 금지)
import { FALLBACK_GRADE_COLOR, FALLBACK_GRADE_LABEL, GRADE_COLOR, GRADE_LABEL } from '../constants';
import type { DefectGrade } from '../types';

interface GradeBadgeProps {
  grade: DefectGrade;
}

export function GradeBadge({ grade }: GradeBadgeProps) {
  const color = GRADE_COLOR[grade] ?? FALLBACK_GRADE_COLOR;
  const label = GRADE_LABEL[grade] ?? FALLBACK_GRADE_LABEL;

  return (
    <span
      className="inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-semibold text-white"
      style={{ backgroundColor: color }}
    >
      <span className="inline-block h-1.5 w-1.5 rounded-full bg-white/80" />
      {label}
    </span>
  );
}
