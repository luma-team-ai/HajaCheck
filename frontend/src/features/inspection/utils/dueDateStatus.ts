// 다음점검일-오늘 기준 상태 파생 — facility feature의 utils/inspectionCycleStatus.ts와 동일 규칙을
// feature 로컬로 복제(cross-feature import 금지, React_코드_컨벤션.md §1).
// 초과(overdue)=D+n(레드) / 임박(upcoming, ≤7일)=D-n(앰버) / 여유이내(grace, ≤60일)=D-n(회색) / 여유(safe)=그린.
const UPCOMING_DAYS_THRESHOLD = 7;
const GRACE_DAYS_THRESHOLD = 60;

export type DueDateStatusKind = 'overdue' | 'upcoming' | 'grace' | 'safe';

export interface DueDateStatus {
  kind: DueDateStatusKind;
  label: string;
}

const MS_PER_DAY = 1000 * 60 * 60 * 24;

function diffInCalendarDays(dateStr: string, today: Date): number {
  const target = new Date(`${dateStr}T00:00:00`);
  const targetMidnight = new Date(target.getFullYear(), target.getMonth(), target.getDate());
  const todayMidnight = new Date(today.getFullYear(), today.getMonth(), today.getDate());
  return Math.round((targetMidnight.getTime() - todayMidnight.getTime()) / MS_PER_DAY);
}

export function deriveDueDateStatus(
  nextInspectionDueAt: string | null,
  today: Date = new Date(),
): DueDateStatus {
  if (!nextInspectionDueAt) {
    return { kind: 'safe', label: '여유' };
  }

  const diffDays = diffInCalendarDays(nextInspectionDueAt, today);

  if (diffDays < 0) {
    return { kind: 'overdue', label: `D+${Math.abs(diffDays)}` };
  }
  if (diffDays <= UPCOMING_DAYS_THRESHOLD) {
    return { kind: 'upcoming', label: `D-${diffDays}` };
  }
  if (diffDays <= GRACE_DAYS_THRESHOLD) {
    return { kind: 'grace', label: `D-${diffDays}` };
  }
  return { kind: 'safe', label: '여유' };
}
