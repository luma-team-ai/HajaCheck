// 등급 배지 — 목록 패널 카드 우측 상단 전용. 색상은 GRADE_COLOR에서만 가져온다(하드코딩 금지).
// Figma 대조 결과 "중대"/"주의" 같은 등급 단어 대신 "등급 {A~E}" 형식으로 표기(2026-07-17).
import { getGradeColor } from '../constants';
import type { DefectGrade } from '../types';

interface GradeBadgeProps {
  /** 등급 API 미연동(#661)으로 값이 없을 수 있음 — null이면 "등급 미정" 회색 배지로 폴백 */
  grade: DefectGrade | null;
}

export function GradeBadge({ grade }: GradeBadgeProps) {
  const color = getGradeColor(grade);

  return (
    <span
      className="inline-flex shrink-0 items-center rounded-full px-2 py-0.5 text-[11px] font-semibold text-white"
      style={{ backgroundColor: color }}
    >
      {grade ? `등급 ${grade}` : '등급 미정'}
    </span>
  );
}
