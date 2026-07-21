import { INSPECTION_CYCLE_COLOR_CLASS } from '../inspectionCycleColors';
import { deriveInspectionCycleStatus } from '../utils/inspectionCycleStatus';

type Props = {
  nextInspectionDueAt: string | null;
  /** 상태 계산 기준일 — 미지정 시 실제 오늘(new Date()). 데모 화면은 고정 기준일(INSPECTION_CYCLE_DEMO_TODAY)을 주입한다. */
  today?: Date;
};

const BADGE_CLASS_BY_KIND = {
  overdue: `${INSPECTION_CYCLE_COLOR_CLASS.overdueBadgeBg} ${INSPECTION_CYCLE_COLOR_CLASS.overdueBadgeText}`,
  upcoming: `${INSPECTION_CYCLE_COLOR_CLASS.upcomingBadgeBg} ${INSPECTION_CYCLE_COLOR_CLASS.upcomingBadgeText}`,
  grace: `${INSPECTION_CYCLE_COLOR_CLASS.graceBadgeBg} ${INSPECTION_CYCLE_COLOR_CLASS.graceBadgeText}`,
  safe: `${INSPECTION_CYCLE_COLOR_CLASS.safeBadgeBg} ${INSPECTION_CYCLE_COLOR_CLASS.safeBadgeText}`,
} as const;

const DOT_CLASS_BY_KIND = {
  overdue: INSPECTION_CYCLE_COLOR_CLASS.overdueDotBg,
  upcoming: INSPECTION_CYCLE_COLOR_CLASS.upcomingDotBg,
  grace: INSPECTION_CYCLE_COLOR_CLASS.graceDotBg,
  safe: INSPECTION_CYCLE_COLOR_CLASS.safeDotBg,
} as const;

// 다음점검일 기준 D-day 뱃지 — 상태 파생은 utils/inspectionCycleStatus.ts 단일 소스(handoff §2)
export function InspectionCycleStatusBadge({ nextInspectionDueAt, today }: Props) {
  const status = deriveInspectionCycleStatus(nextInspectionDueAt, today);

  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-bold ${BADGE_CLASS_BY_KIND[status.kind]}`}
    >
      <span className={`h-1.5 w-1.5 rounded-full ${DOT_CLASS_BY_KIND[status.kind]}`} aria-hidden="true" />
      {status.label}
    </span>
  );
}
