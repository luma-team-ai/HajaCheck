// 다음점검일-오늘 기준 상태 파생 — handoff §2 임계값 규칙을 상수화(하드코딩 산재 방지).
// 초과(overdue)=D+n(레드) / 임박(upcoming, ≤7일)=D-n(앰버) / 여유이내(grace, ≤60일)=D-n(회색) / 여유(safe)=그린.
export const INSPECTION_CYCLE_STATUS_THRESHOLD = {
  /** 이 일수 이내로 다가오면 "임박" */
  upcomingDays: 7,
  /** 이 일수 이내면 "여유이내"(회색), 초과하면 "여유"(그린) */
  graceDays: 60,
} as const;

export type InspectionCycleStatusKind = 'overdue' | 'upcoming' | 'grace' | 'safe';

export interface InspectionCycleStatusResult {
  kind: InspectionCycleStatusKind;
  /** 뱃지 라벨 — D+n / D-n / 여유 */
  label: string;
  /** nextInspectionDueAt - today (일). 음수=지남, 양수=남음 */
  diffDays: number;
}

const MS_PER_DAY = 1000 * 60 * 60 * 24;

function diffInCalendarDays(dateStr: string, today: Date): number {
  const target = new Date(`${dateStr}T00:00:00`);
  const targetMidnight = new Date(target.getFullYear(), target.getMonth(), target.getDate());
  const todayMidnight = new Date(today.getFullYear(), today.getMonth(), today.getDate());
  return Math.round((targetMidnight.getTime() - todayMidnight.getTime()) / MS_PER_DAY);
}

export function deriveInspectionCycleStatus(
  nextInspectionDueAt: string | null,
  today: Date = new Date(),
): InspectionCycleStatusResult {
  if (!nextInspectionDueAt) {
    return { kind: 'safe', label: '여유', diffDays: Number.POSITIVE_INFINITY };
  }

  const diffDays = diffInCalendarDays(nextInspectionDueAt, today);

  if (diffDays < 0) {
    return { kind: 'overdue', label: `D+${Math.abs(diffDays)}`, diffDays };
  }
  if (diffDays <= INSPECTION_CYCLE_STATUS_THRESHOLD.upcomingDays) {
    return { kind: 'upcoming', label: `D-${diffDays}`, diffDays };
  }
  if (diffDays <= INSPECTION_CYCLE_STATUS_THRESHOLD.graceDays) {
    return { kind: 'grace', label: `D-${diffDays}`, diffDays };
  }
  return { kind: 'safe', label: '여유', diffDays };
}
