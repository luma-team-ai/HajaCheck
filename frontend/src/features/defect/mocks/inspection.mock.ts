import type { DefectAssignee, InspectionFacilityOption, InspectionListItem } from '../types';

// GET /api/inspections 통합 테스트용 목 데이터(HAJA-393/394, #725/#726) — mockDefects(defect.mock.ts)의
// inspectionId(101, 202)와 매칭되는 항목은 defectApi.handlers.ts가 그 하자 데이터로부터
// defectCount/gradeDistribution을 동적으로 계산한다(아래 defectCount/gradeDistribution 초기값은
// 자리표시자). id=301은 아직 하자가 등록되지 않은 빈 상태(empty state) 재현용.
export const mockInspections: InspectionListItem[] = [
  {
    id: 101,
    facilityId: 1,
    facilityName: '강남 오피스타워 A동',
    facilityType: '건물',
    roundNo: 3,
    inspectionDate: '2026-07-01',
    status: 'REVIEWED',
    defectCount: 0,
    gradeDistribution: { A: 0, B: 0, C: 0, D: 0, E: 0 },
    assigneeName: '김도현 검사자',
  },
  {
    id: 202,
    facilityId: 3,
    facilityName: '한강대교 북단',
    facilityType: '교량',
    roundNo: 1,
    inspectionDate: '2026-07-03',
    status: 'ANALYZED',
    defectCount: 0,
    gradeDistribution: { A: 0, B: 0, C: 0, D: 0, E: 0 },
    assigneeName: '이서연 검사자',
  },
  {
    id: 301,
    facilityId: 2,
    facilityName: '판교 테크노밸리 B동',
    facilityType: '건물',
    roundNo: 2,
    inspectionDate: '2026-06-20',
    status: 'REPORTED',
    defectCount: 0,
    gradeDistribution: { A: 0, B: 0, C: 0, D: 0, E: 0 },
    assigneeName: null,
  },
];

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
