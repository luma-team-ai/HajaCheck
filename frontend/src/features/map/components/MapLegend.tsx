import { GRADE_COLOR } from '../constants';
import type { DefectGrade } from '../types';

export function MapLegend() {
  const grades: DefectGrade[] = ['A', 'B', 'C', 'D', 'E'];

  return (
    // border-[#d4d4d8]/text-[#52525b]: Figma 범례 전용 색상 — styles/tokens.css의 --color-border(#e4e4e7),
    // --color-text-muted(#7a7582)와 값이 달라 시맨틱 토큰으로 치환하지 않고 유지(P3, 2026-07-16 검토,
    // SelectedFacilityPopup과 동일 사유)
    // bottom-8: shared/components/BottomNavBarFab.tsx(상담사 버튼)의 bottom-8과 세로 baseline을
    // 맞춰 시각적으로 통일한다(#570) — 지도 패널 하단이 이제 뷰포트 하단과 일치하므로(루트 높이
    // 실측 고정) absolute(패널 기준)와 fixed(뷰포트 기준) 값이 같은 오프셋으로 정렬된다.
    <div className="absolute bottom-8 left-4 z-10 flex items-center gap-3 rounded-full border border-[#d4d4d8] bg-white/90 px-4 py-2 text-xs shadow-sm backdrop-blur-[2px]">
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
