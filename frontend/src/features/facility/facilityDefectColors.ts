// 하자 등급/변화 배지 색상 — React_코드_컨벤션.md §8 "컴포넌트에 hex 하드코딩 금지".
// dashboard/colors.ts GRADE_BG_CLASS와 동일 계열이지만 feature 간 직접 import 금지(§1)라 로컬로 재정의한다.
import type { FacilityDefectGrade } from './types';

export const FACILITY_DEFECT_GRADE_BADGE_CLASS: Record<FacilityDefectGrade, string> = {
  A: 'bg-[#e3f5e6] text-[#16a34a]',
  B: 'bg-[#eef6df] text-[#65a30d]',
  C: 'bg-[#fdf6d5] text-[#b58b0a]',
  D: 'bg-[#fdf0d5] text-[#b5670a]',
  E: 'bg-[#fde8e8] text-[#dc2626]',
};

export const DEFECT_CHANGE_TYPE_BADGE_CLASS = {
  worsened: 'bg-[#fde8e8] text-[#dc2626]',
  new: 'bg-[#fdf0d5] text-[#b5670a]',
  unchanged: 'bg-[#f0f1f3] text-[#6b7280]',
  resolved: 'bg-[#e3f5e6] text-[#16a34a]',
} as const;