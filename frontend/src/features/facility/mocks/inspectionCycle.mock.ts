import type { InspectionCycleStatusRow } from '../types';

// 전체 시설물 점검 주기 현황 — handoff §3 예시 5행(합성). 백엔드 계약에 없는 필드(type/lastInspectedAt/
// assigneeName)를 포함하므로 feature 로컬 목 모듈로만 관리한다. 실연동 확장 지점은 types.ts 주석 참고.
//
// facilityApi.handlers.ts의 `facilities` 모듈 스코프 mutable 배열과 동일한 패턴 — 저장(POST
// /schedule) 성공 시 해당 행을 갱신해야 좌측 카드와 우측 현황 테이블이 같은 값을 보여준다(react-reviewer P1).
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
