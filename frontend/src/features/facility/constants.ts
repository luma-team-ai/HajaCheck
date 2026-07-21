// Figma(dev-04-01) 등록 모달 "시설물 유형" 셀렉트 예시 옵션 — 백엔드 type은 자유 문자열(≤20자)이라
// 여기 목록은 UI상 기본 선택지일 뿐 서버 검증값은 아니다.
export const FACILITY_TYPE_OPTIONS = ['건물', '교량', '터널', '도로', '기타'] as const;

// 하자 상세 — 조치 상태 스테퍼(dev-04-02, #489). 순서는 백엔드 DefectStatus 정의 순서와 동일.
import type { DefectChangeType, FacilityDefectStatus } from './types';

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
