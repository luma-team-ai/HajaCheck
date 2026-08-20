import type { InspectionStatus } from '../../shared/constants/inspectionStatus';

// 점검(회차) id/facilityId/roundNo/inspectionDate/status — 공통 필드의 단일 소스(#1693 코드리뷰
// P2·G6).
//
// 왜 여기(mocks/fixtures/, 특정 feature 소유 아님)에 두는가: 이 공통 필드는 inspection
// feature(GET /api/inspections/{id} 핸들러 — useInspectionResultReal 결과 뷰어)와 defect
// feature(GET /api/inspections 목록 핸들러가 참조하는 mockInspections — InspectionTable, +
// useInspection 점검 상세 헤더 점검 상태 배지) 양쪽에서 쓰인다. 과거엔 두 feature가 각자 하드코딩
// 배열을 갖고 있었는데(id 101/202/301, status까지 완전히 동일값이라 지금까지는 우연히 화면이
// 맞아떨어졌다) 실제로는 소스가 셋(inspectionApi.handlers.ts 인라인 / defect/mocks/
// inspection.mock.ts / 여기)이라, 한쪽만 status를 바꾸면 화면마다 다른 상태가 노출되는 —
// 이슈 #1693이 고치려던 증상(목록·상세 상태 불일치) 그 자체의 목 버전이 재현되는 회귀 위험이
// 있었다(G6 지적). 두 feature 중 한쪽이 다른 feature의 mocks/를 직접 import하면 "feature 간 직접
// import 금지" 컨벤션(React_코드_컨벤션.md §1)을 어기므로, mocks/handlers.ts와 동일하게 특정
// feature가 소유하지 않는 최상위 위치로 이 공통 필드만 승격한다.
//
// defectCount/gradeDistribution/facilityName/type/assigneeName처럼 목록 화면 전용 필드는 대체하지
// 않는다 — defect/mocks/inspection.mock.ts의 mockInspections가 이 배열을 기반(id 매칭)으로 그
// 필드들만 로컬에서 확장한다(defect/mocks → mocks/fixtures 방향은 "하위 feature → 공용"이라
// 컨벤션에 걸리지 않는다. 반대 방향만 금지).
//
// 값은 기존 세 소스의 하드코딩과 동일하게 유지한다(id/facilityId/roundNo/inspectionDate/status
// 전부 동일했음) — 그래서 이 통합 자체로는 어떤 기존 테스트의 기댓값도 바뀌지 않는다.
export interface MockInspectionSummary {
  id: number;
  facilityId: number;
  roundNo: number;
  inspectionDate: string; // YYYY-MM-DD
  status: InspectionStatus;
}

export const mockInspectionSummaries: MockInspectionSummary[] = [
  { id: 101, facilityId: 1, roundNo: 3, inspectionDate: '2026-07-01', status: 'REVIEWED' },
  { id: 202, facilityId: 3, roundNo: 1, inspectionDate: '2026-07-03', status: 'ANALYZED' },
  { id: 301, facilityId: 2, roundNo: 2, inspectionDate: '2026-06-20', status: 'REPORTED' },
];
