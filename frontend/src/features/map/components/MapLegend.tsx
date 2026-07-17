import { GRADE_COLOR } from '../constants';
import type { DefectGrade } from '../types';

export function MapLegend() {
  const grades: DefectGrade[] = ['A', 'B', 'C', 'D', 'E'];

  return (
    // border-[#d4d4d8]/text-[#52525b]: Figma 범례 전용 색상 — styles/tokens.css의 --color-border(#e4e4e7),
    // --color-text-muted(#7a7582)와 값이 달라 시맨틱 토큰으로 치환하지 않고 유지(P3, 2026-07-16 검토,
    // SelectedFacilityPopup과 동일 사유)
    <div className="absolute bottom-4 left-4 z-10 flex items-center gap-3 rounded-full border border-[#d4d4d8] bg-white/90 px-4 py-2 text-xs shadow-sm backdrop-blur-[2px]">
      <span className="font-normal text-[#52525b] text-[12px] leading-[18px]">
        등급 범례
      </span>
      {grades.map((grade) => (
        <span key={grade} className="flex items-center gap-1.5">
          <span
            className="inline-block h-2.5 w-2.5 rounded-full"
            style={{ backgroundColor: GRADE_COLOR[grade] }}
          />
          <span className="font-normal text-[#52525b] text-[12px] leading-[18px]">
            {grade}
          </span>
        </span>
      ))}
    </div>
  );
}
