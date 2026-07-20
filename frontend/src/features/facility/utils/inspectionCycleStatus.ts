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

// ─────────────────────────────────────────────────────────────────────────────
// 데모(MSW 목) 전용 고정 기준일 — 이 화면(dev-04-03)은 백엔드 계약이 없어 목 데이터로만 동작한다.
// 목 다음점검일(예: 강남 오피스타워 A동 2026-12-21)이 Figma 디자인의 D-day 뱃지(D-42)와 일치하도록
// 상태 계산·저장의 '오늘'을 이 날짜로 고정한다(실제 오늘 기준으로는 154일 뒤라 전부 "여유"로만 보임).
// 화면 컴포넌트는 이 값을 today로 주입해 쓰며, 단위 테스트는 주입하지 않아 new Date() 기본값(fakeTimers)을 탄다.
// ⚠️ 실 API 연동(계약확장 #284) 시 이 상수와 today 주입을 제거하고 new Date()로 되돌릴 것.
export const INSPECTION_CYCLE_DEMO_TODAY = new Date(2026, 10, 9); // 2026-11-09 (로컬)

// 데모 기준일 + 개월수 → YYYY-MM-DD(로컬 캘린더). 저장 시 다음점검일을 데모 기준일 기준으로 산정해
// 저장 후에도 뱃지(같은 기준일로 파생)와 어긋나지 않게 한다(실제 오늘 기준이면 짧은 주기 저장이
// 데모 기준일보다 과거가 돼 '초과'로 오표시됨). computeNextInspectionDueAt(실제 오늘 기준, 등록 플로우용)과
// 별도로 두어 기존 등록 시나리오·테스트에 영향을 주지 않는다.
export function computeDemoNextInspectionDueAt(months: number): string {
  const due = new Date(INSPECTION_CYCLE_DEMO_TODAY.getTime());
  due.setMonth(due.getMonth() + months);
  const year = due.getFullYear();
  const month = String(due.getMonth() + 1).padStart(2, '0');
  const day = String(due.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}
