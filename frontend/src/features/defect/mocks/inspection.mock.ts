import { mockInspectionSummaries } from '../../../mocks/fixtures/inspectionSummary.mock';
import type { DefectAssignee, InspectionFacilityOption, InspectionListItem } from '../types';

// GET /api/inspections 통합 테스트용 목 데이터(HAJA-393/394, #725/#726) — mockDefects(defect.mock.ts)의
// inspectionId(101, 202)와 매칭되는 항목은 defectApi.handlers.ts가 그 하자 데이터로부터
// defectCount/gradeDistribution을 동적으로 계산한다(아래 defectCount/gradeDistribution 초기값은
// 자리표시자). id=301은 아직 하자가 등록되지 않은 빈 상태(empty state) 재현용.
//
// id/facilityId/roundNo/inspectionDate/status는 mocks/fixtures/inspectionSummary.mock.ts를 단일
// 소스로 참조한다(G6 발견 — #1693). 과거엔 이 배열이 그 값을 각자 하드코딩해, GET /api/inspections
// (목록, InspectionTable)와 GET /api/inspections/:id(상세 배지, useInspection)의 status가 서로 다른
// 파일에서 따로 관리돼 한쪽만 고치면 목록·상세가 갈라지는 위험이 있었다 — 이슈 #1693이 고치려던
// 증상(목록·상세 상태 불일치)의 목 버전. import 방향은 features/defect/mocks(하위) →
// mocks/fixtures(공용)라 feature 간 직접 import 금지 컨벤션에 걸리지 않는다(반대 방향만 금지).
// defectCount/gradeDistribution/facilityName/type/assigneeName은 이 목록 화면 전용 필드라 그대로
// 이 파일에서 확장한다.
const INSPECTION_LIST_EXTRAS: Record<
  number,
  Pick<InspectionListItem, 'facilityName' | 'type' | 'defectCount' | 'gradeDistribution' | 'assigneeName'>
> = {
  101: {
    facilityName: '강남 오피스타워 A동',
    type: 'REGULAR',
    defectCount: 0,
    gradeDistribution: { A: 0, B: 0, C: 0, D: 0, E: 0 },
    assigneeName: '김도현 검사자',
  },
  202: {
    facilityName: '한강대교 북단',
    type: 'DETAILED',
    defectCount: 0,
    gradeDistribution: { A: 0, B: 0, C: 0, D: 0, E: 0 },
    assigneeName: '이서연 검사자',
  },
  301: {
    facilityName: '판교 테크노밸리 B동',
    type: 'EMERGENCY',
    defectCount: 0,
    gradeDistribution: { A: 0, B: 0, C: 0, D: 0, E: 0 },
    assigneeName: null,
  },
};

export const mockInspections: InspectionListItem[] = mockInspectionSummaries.map((summary) => ({
  ...summary,
  ...INSPECTION_LIST_EXTRAS[summary.id],
}));

// 점검 목록 필터의 시설물 select 옵션 목 — inspection feature의 mockFacilityOptions와는 별개
// (feature 간 직접 import 금지, 값만 동일 데모 시설물 기준으로 로컬 복제).
export const mockInspectionFacilityOptions: InspectionFacilityOption[] = [
  { id: 1, name: '강남 오피스타워 A동' },
  { id: 2, name: '판교 테크노밸리 B동' },
  { id: 3, name: '한강대교 북단' },
];

// 하자 상세 모달 "담당자" select 옵션 목 — facility feature의 mockFacilityAssignableUsers와 동일 값을
// feature 간 직접 import 금지 컨벤션에 따라 로컬로 복제한다(#690 재사용 대상, 실 API 없음).
export const mockDefectAssignees: DefectAssignee[] = [
  { id: 101, name: '김도현 검사자' },
  { id: 102, name: '이서연 검사자' },
  { id: 103, name: '박지훈 관리자' },
];
