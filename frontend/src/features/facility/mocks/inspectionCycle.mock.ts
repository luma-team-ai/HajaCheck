import type { InspectionCycleStatusRow } from '../types';

// ⚠️ #1136 이후 프로덕션 미사용(legacy) — useInspectionCycleStatusRows가 이제 실
// GET /api/facilities/status(facilityApi.getStatusList)를 쓴다. 이 모듈은 아무 프로덕션
// 코드도 더 이상 import하지 않으며, resetInspectionCycleStatusMockStore만 기존(머지된)
// InspectionCycleSettingsPage.test.tsx의 afterEach가 참조해 남겨둔다(삭제 시 그 테스트가 깨짐).
// 새 코드에서 getInspectionCycleStatusRows/updateInspectionCycleStatusRow를 데이터 소스로
// 다시 끌어다 쓰지 말 것 — 실 회사 소유 시설물과 무관한 합성 mock이다(#1129 원인).
const INITIAL_ROWS: InspectionCycleStatusRow[] = [
  {
    id: 1,
    name: 'B1 발전기실',
    type: '정기',
    cycleMonths: 3,
    lastInspectedAt: '2026-06-10',
    nextInspectionDueAt: '2026-09-10',
    assigneeName: '김관리',
  },
  {
    id: 2,
    name: '1F 메인 로비',
    type: '정밀',
    cycleMonths: 6,
    lastInspectedAt: '2026-03-25',
    nextInspectionDueAt: '2026-09-25',
    assigneeName: '이담당',
  },
  {
    id: 3,
    // breadcrumb(router.tsx) "강남 오피스타워 A동"과 이름을 맞춘다 — 기본 선택 시설물이 화면 상단
    // breadcrumb과 다른 이름으로 보이던 불일치 정리(react-reviewer P3).
    name: '강남 오피스타워 A동',
    type: '정기',
    cycleMonths: 6,
    lastInspectedAt: '2026-06-21',
    nextInspectionDueAt: '2026-12-21',
    assigneeName: '박책임',
  },
  {
    id: 4,
    name: '옥상 공조탑',
    type: '정기',
    cycleMonths: 12,
    lastInspectedAt: '2026-01-15',
    nextInspectionDueAt: '2027-01-15',
    assigneeName: '최엔지니어',
  },
  {
    id: 5,
    name: '지하 주차장',
    type: '정밀',
    cycleMonths: 12,
    lastInspectedAt: '2026-02-10',
    nextInspectionDueAt: '2027-02-10',
    assigneeName: '김관리',
  },
];

let inspectionCycleStatusRows: InspectionCycleStatusRow[] = [...INITIAL_ROWS];

export function getInspectionCycleStatusRows(): InspectionCycleStatusRow[] {
  return inspectionCycleStatusRows;
}

// 저장 성공 시 해당 행의 주기·다음점검일을 갱신 — useSetInspectionSchedule onSuccess에서 호출.
export function updateInspectionCycleStatusRow(
  id: number,
  patch: Partial<Pick<InspectionCycleStatusRow, 'cycleMonths' | 'nextInspectionDueAt'>>,
): void {
  inspectionCycleStatusRows = inspectionCycleStatusRows.map((row) =>
    row.id === id ? { ...row, ...patch } : row,
  );
}

// 테스트 간 mutable 상태 격리용 — facilityApi.handlers.ts의 resetFacilityMockStore와 동일 목적.
export function resetInspectionCycleStatusMockStore(): void {
  inspectionCycleStatusRows = [...INITIAL_ROWS];
}
