// PR머신 review P3: import는 파일 최상단에 두는 관례(다른 facility 파일들과 동일)
import type { DefectChangeType, FacilityDefectStatus, FacilityInitialGrade } from './types';

// 등록 모달 "시설물 유형" 셀렉트 옵션 — {종류}-{점검유형}-{주기} 조합 12종(#731).
// 백엔드 type은 여전히 자유 문자열(≤20자) 컬럼이라 선택한 value 문자열을 그대로 type으로 저장한다
// (DB 스키마 변경 없음). cycleMonths는 UI 전용 파생값 — 선택 시 inspectionCycleMonths·
// nextInspectionDueAt을 자동 계산하기 위한 매핑이며 서버로는 별도 전송되지 않는다.
export interface FacilityTypeOption {
  readonly value: string;
  readonly cycleMonths: number;
}

export const FACILITY_TYPE_OPTIONS: readonly FacilityTypeOption[] = [
  { value: '건물-긴급-1개월', cycleMonths: 1 },
  { value: '건물-정기-4개월', cycleMonths: 4 },
  { value: '건물-정밀-24개월', cycleMonths: 24 },
  { value: '교량-긴급-1개월', cycleMonths: 1 },
  { value: '교량-정기-4개월', cycleMonths: 4 },
  { value: '교량-정밀-12개월', cycleMonths: 12 },
  { value: '도로-긴급-1개월', cycleMonths: 1 },
  { value: '도로-정기-4개월', cycleMonths: 4 },
  { value: '도로-정밀-12개월', cycleMonths: 12 },
  { value: '기타-긴급-1개월', cycleMonths: 1 },
  { value: '기타-정기-4개월', cycleMonths: 4 },
  { value: '기타-정밀-12개월', cycleMonths: 12 },
];

// 선택된 type 문자열(자유 입력 포함)에 매칭되는 옵션의 점검주기(개월)를 조회한다.
// 12개 옵션 중 정확히 일치하는 값이 없으면(예외적 케이스 — 과거 데이터, 직접 입력 등) null을
// 반환해 validateFacilityForm.toCreateFacilityRequest가 기존처럼 null을 유지하게 한다.
export function findFacilityTypeCycleMonths(type: string): number | null {
  const option = FACILITY_TYPE_OPTIONS.find((candidate) => candidate.value === type.trim());
  return option ? option.cycleMonths : null;
}

// 등록 모달 "초기 등급 설정" pill 토글 옵션(#628/HAJA-347) — backend FacilityInitialGrade와 순서 정합.
export const FACILITY_INITIAL_GRADE_OPTIONS: FacilityInitialGrade[] = ['A', 'B', 'C', 'D', 'E'];

// 하자 상세 — 조치 상태 스테퍼(dev-04-02, #489). 순서는 백엔드 DefectStatus 정의 순서와 동일.

export const FACILITY_DEFECT_STATUS_ORDER: FacilityDefectStatus[] = [
  'DETECTED',
  'CONFIRMED',
  'ACTION_PENDING',
  'IN_PROGRESS',
  'RESOLVED',
];

export const FACILITY_DEFECT_STATUS_LABEL: Record<FacilityDefectStatus, string> = {
  DETECTED: '신규',
  CONFIRMED: '검수확정',
  ACTION_PENDING: '조치대기',
  IN_PROGRESS: '조치중',
  RESOLVED: '조치완료',
};

// 회차 간 비교 — 하자 변화 목록 배지 라벨(dev-04-02, #489)
export const DEFECT_CHANGE_TYPE_LABEL: Record<DefectChangeType, string> = {
  worsened: '악화',
  new: '신규',
  unchanged: '유지',
  resolved: '조치완료',
};
