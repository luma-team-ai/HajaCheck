// 대시보드 색상 클래스 단일 관리 — React_코드_컨벤션.md §8 "컴포넌트에 hex 하드코딩 금지".
// 원본 dashboard.css가 `--dashboard-color-danger` CSS 변수로 하던 "하드코딩 대신 단일 관리"
// 역할을 Tailwind 전환 후 이 모듈이 이어받는다. 2회 이상 등장하는 색은 전부 여기로 모은다.
//
// ⚠️ styles/tokens.css(@theme) 승격은 하지 않는다 — 타 오너(#227) 자산이라 충돌방지 규칙①에 걸리고,
//    여기 값들은 기존 토큰(--color-primary/--color-border 등)과 실제 색이 달라 재사용도 불가능하다.
//    따라서 feature 로컬 상수까지만 둔다.
//
// ⚠️ Tailwind 스캐너는 소스 텍스트에 "완전한 클래스명"이 리터럴로 존재할 때만 그 클래스를 생성한다.
//    `text-[${COLOR}]` 같은 조각 조립은 절대 금지(클래스가 생성되지 않음).
//    그래서 같은 색이라도 용도(bg/text/border)·breakpoint·important 유무마다 별도 리터럴로 둔다.
//
// ⚠️ `...Important`(뒤에 `!`)가 붙은 항목은 shared/styles/layout.css의 공용 클래스를 덮기 위한 것이다.
//    layout.css는 un-layered CSS이고 Tailwind v4 유틸리티는 `@layer utilities` 안에 들어가는데,
//    CSS Cascade Layers 규칙상 un-layered 선언이 특이도·소스순서와 무관하게 layered 선언을 항상 이긴다.
//    → layout.css 공용 클래스와 같은 속성을 유틸리티로 덮을 때는 반드시 `!`가 필요하다.

import type { DefectGrade } from './types';

export const DASHBOARD_COLOR_CLASS = {
  /** 위험/하락 지표 — KPI 하락률 */
  dangerText: 'text-[#dc2626]',
  /** 위험 경고문 — layout.css `.dashboard-card-status`(color:#999)를 덮어야 해서 important 필수 */
  dangerTextImportant: 'text-[#dc2626]!',
  /** 정상/상승 지표 — KPI 상승률 */
  successText: 'text-[#16a34a]',
  /** 주의 환기 점 — KPI 알림 dot */
  alertDotBg: 'bg-[#f97316]',
  /** 공통 구분선 — KPI 컬럼 구분, 처리대기 서브카드 테두리 */
  dividerBorder: 'border-[#ececec]',
  /** 1100px 미만에서 KPI 컬럼이 2단으로 접힐 때의 가로 구분선 */
  dividerBorderBottomNarrow: 'max-[1100px]:border-b-[#ececec]',
  /** 보조 라벨 — KPI 라벨, 테이블 헤더 */
  labelText: 'text-[#888]',
  /** 흐린 보조 텍스트 — 처리대기 부제/경과시간 */
  mutedText: 'text-[#999]',
  /** 본문 텍스트 — AI 브리핑 본문, 처리대기 버튼 라벨 */
  bodyText: 'text-[#333]',
  /** AI 강조색 */
  accentBg: 'bg-[#4a5cff]',
  accentText: 'text-[#4a5cff]',
  /** 점검 상태 배지 — 배경+전경 한 쌍 */
  statusBadgeBlue: 'bg-[#e6ecff] text-[#3452e0]',
  statusBadgeOrange: 'bg-[#fdf0d5] text-[#b5670a]',
  statusBadgeGreen: 'bg-[#e3f5e6] text-[#16a34a]',
  /** 등급 미분류 배지 배경 — BE PendingPriorityResponse.grade가 null일 때(HAJA-17 dev-03-01 DTO 정합) */
  gradeUnknownBg: 'bg-[#9ca3af]',
} as const;

/**
 * 하자 등급별 배경색 — A(양호,초록) → E(중대,빨강) 그라데이션 (docs 시안 기준).
 * 접근자는 utils/gradeDistribution.ts의 getGradeBgClass.
 */
export const GRADE_BG_CLASS: Record<DefectGrade, string> = {
  A: 'bg-[#16a34a]',
  B: 'bg-[#65a30d]',
  C: 'bg-[#eab308]',
  D: 'bg-[#f97316]',
  E: 'bg-[#dc2626]',
};
