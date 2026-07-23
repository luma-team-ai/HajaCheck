// 다음 점검일 D-day 임박도 — inspection feature의 dueDateStatus.ts와 동일 규칙을
// feature 로컬로 복제(cross-feature import 금지, React_코드_컨벤션.md §1).
// overdue(D+n, 발생 안 함 — BE dDay는 항상 0 이상)/upcoming(≤7일)/grace(≤60일)/safe(그 외).
const UPCOMING_DAYS_THRESHOLD = 7;
const GRACE_DAYS_THRESHOLD = 60;

export type UpcomingInspectionStatusKind = 'overdue' | 'upcoming' | 'grace' | 'safe';

export function deriveUpcomingInspectionStatusKind(dDay: number): UpcomingInspectionStatusKind {
  if (dDay < 0) {
    return 'overdue';
  }
  if (dDay <= UPCOMING_DAYS_THRESHOLD) {
    return 'upcoming';
  }
  if (dDay <= GRACE_DAYS_THRESHOLD) {
    return 'grace';
  }
  return 'safe';
}
