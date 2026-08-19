import type { InspectionStatus } from '../../shared/constants/inspectionStatus';

// GET /api/inspections/{id}(점검 회차 단건 상세) 응답의 단일 목 데이터 소스(#1693 코드리뷰 P2).
//
// 왜 여기(mocks/fixtures/, 특정 feature 소유 아님)에 두는가: 이 엔드포인트는 inspection
// feature(useInspectionResultReal — 결과 뷰어)와 defect feature(useInspection — 점검 상세 헤더의
// 점검 상태 배지, #1693)가 각각 독립적으로 호출한다. 과거엔 두 feature가 같은 엔드포인트를 위해
// 각자 하드코딩 배열을 갖고 있었는데(id 101/202/301, status까지 완전히 동일값이라 지금까지는 우연히
// 화면이 맞아떨어졌다), mocks/handlers.ts 전역 체인에서는 먼저 등록된 inspectionHandlers만
// 실제로 응답하고 defect 쪽 목은 도달 불가능한 죽은 코드였다(P2 지적). 두 feature 중 한쪽이 다른
// feature의 mocks/를 직접 import하면 "feature 간 직접 import 금지" 컨벤션(React_코드_컨벤션.md
// §1)을 어기므로, mocks/handlers.ts와 동일하게 특정 feature가 소유하지 않는 최상위 위치로 최소
// 공통 데이터만 승격한다. defect feature의 풍부한 목록용 mockInspections(defect/mocks/
// inspection.mock.ts — defectCount/gradeDistribution 등 이 엔드포인트에 없는 필드까지 포함)를
// 대체하는 것은 아니다 — 어디까지나 "점검 단건 상세" 엔드포인트 전용 최소 데이터.
//
// 값은 기존 두 feature의 하드코딩과 동일하게 유지한다(id/facilityId/roundNo/inspectionDate/status
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
