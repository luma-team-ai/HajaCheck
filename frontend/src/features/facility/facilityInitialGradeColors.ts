// 등록 모달 "초기 등급 설정" pill 토글 선택 색상(#628/HAJA-347) — React_코드_컨벤션.md §8
// "컴포넌트에 hex 하드코딩 금지". facilityDefectColors.ts의 FACILITY_DEFECT_GRADE_BADGE_CLASS와
// 시각적으로 같은 팔레트를 재사용하지만, initialGrade는 별개의 독립 개념(types.ts 주석 참고)이라
// 개념 결합을 피하기 위해 이 파일에 별도로 정의한다.
import type { FacilityInitialGrade } from './types';

export const FACILITY_INITIAL_GRADE_SELECTED_CLASS: Record<FacilityInitialGrade, string> = {
  A: 'border-[#16a34a] bg-[#e3f5e6] text-[#16a34a]',
  B: 'border-[#65a30d] bg-[#eef6df] text-[#65a30d]',
  C: 'border-[#b58b0a] bg-[#fdf6d5] text-[#b58b0a]',
  D: 'border-[#b5670a] bg-[#fdf0d5] text-[#b5670a]',
  E: 'border-[#dc2626] bg-[#fde8e8] text-[#dc2626]',
};

export const FACILITY_INITIAL_GRADE_UNSELECTED_CLASS =
  'border-border bg-surface text-text-muted hover:bg-surface-muted';
