// 시설물 카드 등급 배지 점 색상(HAJA-368) — React_코드_컨벤션.md §8 "컴포넌트에 hex 하드코딩 금지".
// facilityDefectColors.ts/facilityInitialGradeColors.ts와 동일한 A~E hex 팔레트를 쓰되,
// 카드 오버레이 배지는 배경색 pill이 아니라 흰 배경 + 점(dot) 스타일이라 별도 맵으로 둔다.
import type { FacilityInitialGrade } from './types';

export const FACILITY_CARD_GRADE_DOT_COLOR: Record<FacilityInitialGrade, string> = {
  A: '#16a34a',
  B: '#65a30d',
  C: '#b58b0a',
  D: '#b5670a',
  E: '#dc2626',
};

// "하자 N건" 배지(HAJA-515/#1075) — Figma 시안 기준, 등급 점(dot)과 동일 색을 글자색으로 쓰고
// 배경은 그 색의 연한 톤이다(facilityDefectColors.ts의 FACILITY_DEFECT_GRADE_BADGE_CLASS와
// 동일 hex — 등급 팔레트가 화면 전반에서 하나로 정합돼야 하므로 값을 그대로 맞춘다. 카드 배지
// 전용 별도 맵으로 둔 이유는 위 dot 맵과 같은 FacilityInitialGrade 키로 묶어 두기 위함).
export const FACILITY_CARD_DEFECT_COUNT_BADGE_CLASS: Record<FacilityInitialGrade, string> = {
  A: 'bg-[#e3f5e6] text-[#16a34a]',
  B: 'bg-[#eef6df] text-[#65a30d]',
  C: 'bg-[#fdf6d5] text-[#b58b0a]',
  D: 'bg-[#fdf0d5] text-[#b5670a]',
  E: 'bg-[#fde8e8] text-[#dc2626]',
};

// "다음 점검일 D-n" 임박 배지(HAJA-368) 배경색 — tokens.css의 warning-soft-*는 옅은 톤(분석요약
// 노트용)이라 이 필채움(solid) 배지엔 안 맞는다. D등급과 동일 계열(진한 앰버)을 재사용한다.
export const FACILITY_CARD_UPCOMING_BADGE_BG = '#b5670a';
