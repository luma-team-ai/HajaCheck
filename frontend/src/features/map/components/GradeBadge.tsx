// 등급 배지 — 목록 패널 카드 우측 상단 전용. 색상은 GRADE_COLOR에서만 가져온다(하드코딩 금지).
// Figma 대조 결과 "중대"/"주의" 같은 등급 단어 대신 "등급 {A~E}" 형식으로 표기(2026-07-17).
import { FALLBACK_GRADE_COLOR, GRADE_COLOR } from '../constants';
import type { DefectGrade } from '../types';

interface GradeBadgeProps {
  grade: DefectGrade;
}

export function GradeBadge({ grade }: GradeBadgeProps) {
  const color = GRADE_COLOR[grade] ?? FALLBACK_GRADE_COLOR;

  return (
    <span
      className="inline-flex shrink-0 items-center rounded-full px-2 py-0.5 text-[11px] font-semibold text-white"
      style={{ backgroundColor: color }}
    >
      등급 {grade}
    </span>
  );
}
