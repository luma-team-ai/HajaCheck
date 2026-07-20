import type { InspectionCycleStatusRow } from '../types';

// 전체 시설물 점검 주기 현황 — handoff §3 예시 5행(합성). 백엔드 계약에 없는 필드(type/lastInspectedAt/
// assigneeName)를 포함하므로 feature 로컬 목 모듈로만 관리한다. 실연동 확장 지점은 types.ts 주석 참고.
export const mockInspectionCycleStatusRows: InspectionCycleStatusRow[] = [
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
    name: '오피스타워',
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
