// ─────────────────────────────────────────────────────────────────────────────
// 데모(MSW 목) 전용 고정 기준일 — 이 화면(dev-04-03)은 백엔드 계약이 없어 목 데이터로만 동작한다.
// 목 다음점검일(예: 강남 오피스타워 A동 2026-12-21)이 Figma 디자인의 D-day 뱃지(D-42)와 일치하도록
// 상태 계산·저장의 '오늘'을 이 날짜로 고정한다(실제 오늘 기준으로는 154일 뒤라 전부 "여유"로만 보임).
// 화면 컴포넌트는 이 값을 today로 주입해 쓰며, 단위 테스트는 주입하지 않아 new Date() 기본값(fakeTimers)을 탄다.
// ⚠️ 실 API 연동(계약확장 #284) 시 이 상수와 today 주입을 제거하고 new Date()로 되돌릴 것.
//
// react-reviewer P2: 데모 전용 상수/함수를 범용 상태 로직(inspectionCycleStatus.ts)과 분리해,
// 프로덕션 빌드에서 실수로 데모 기준일이 주입되는 침묵 버그를 막는다(호출부는 env 가드를 거쳐야 함 —
// InspectionCycleSettingsPage.tsx의 shouldEnableMocking(import.meta.env) 참조).
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
