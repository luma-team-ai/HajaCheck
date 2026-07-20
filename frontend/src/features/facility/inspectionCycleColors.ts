// 점검 주기 설정(dev-04-03) 색상 클래스 단일 관리 — React_코드_컨벤션.md §8 "컴포넌트에 hex 하드코딩 금지".
// dashboard/colors.ts와 동일한 패턴을 feature 로컬로 재현한다(handoff §4) — feature 간 직접 import는
// 금지(§1)이므로 dashboard 색 모듈을 그대로 가져오지 않고 이 파일에 필요한 색만 다시 둔다.
//
// ⚠️ Tailwind 스캐너는 소스 텍스트에 "완전한 클래스명"이 리터럴로 존재할 때만 그 클래스를 생성한다.
//    `text-[${COLOR}]` 같은 조각 조립은 절대 금지 — 용도별로 완전한 리터럴을 둔다.
//
// ⚠️ 기존 토큰(src/styles/tokens.css --color-*)과 겹치는 색(예: 검정 primary, 공통 보더)은
//    새 hex를 만들지 않고 bg-primary/text-heading/border-border 등 토큰 유틸리티를 그대로 쓴다.
//    여기 두는 것은 토큰에 없는 상태뱃지·세그먼트·토글 전용 색뿐이다.

export const INSPECTION_CYCLE_COLOR_CLASS = {
  /** 세그먼트 토글(정기/정밀/긴급) 트랙 배경 */
  segmentTrackBg: 'bg-[#f0eef2]',
  /** 세그먼트 토글 활성 pill — 흰 배경 + 은은한 그림자 */
  segmentActivePill: 'bg-surface shadow-sm text-heading',
  /** 세그먼트 토글 비활성 텍스트 */
  segmentInactiveText: 'text-text-muted',

  /** 상태뱃지 — 초과(D+n) */
  overdueBadgeBg: 'bg-[#fee2e2]',
  overdueBadgeText: 'text-[#dc2626]',
  overdueDotBg: 'bg-[#dc2626]',
  /** 초과 행 전체 배경 — 연핑크(handoff §2) */
  overdueRowBg: 'bg-[#fef2f2]',

  /** 상태뱃지 — 임박(D-n, ≤7일) */
  upcomingBadgeBg: 'bg-[#fef3c7]',
  upcomingBadgeText: 'text-[#b45309]',
  upcomingDotBg: 'bg-[#eab308]',

  /** 상태뱃지 — 여유이내(D-n, 회색) */
  graceBadgeBg: 'bg-[#f4f4f5]',
  graceBadgeText: 'text-[#71717a]',
  graceDotBg: 'bg-[#a1a1aa]',

  /** 상태뱃지 — 여유(그린 dot) */
  safeBadgeBg: 'bg-[#dcfce7]',
  safeBadgeText: 'text-[#16a34a]',
  safeDotBg: 'bg-[#22c55e]',

  /** 선택된 행 배경 + 좌측 강조 바(inset box-shadow) — RecentInspectionsTable과 동일 패턴 */
  rowSelectedBg: 'bg-[#f4f4f5]',
  rowSelectedBar: 'shadow-[inset_3px_0_0_#18181b]',
  /** 행 키보드 포커스 아웃라인 */
  rowFocusOutline: 'focus-visible:[outline:2px_solid_#18181b] focus-visible:[outline-offset:-2px]',
  /** 미선택 행 hover 배경 */
  rowHoverBg: 'hover:bg-[#fafafa]',

  /** 토글 스위치 — ON/OFF 트랙·손잡이 */
  toggleOnTrackBg: 'bg-primary',
  toggleOffTrackBg: 'bg-[#e4e4e7]',
  toggleThumbBg: 'bg-surface',
} as const;
