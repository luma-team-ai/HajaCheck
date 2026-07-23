import type { FacilityAssignableUser } from '../types';

// 담당자 select 옵션 목 데이터 — 실 API 없음(types.ts 주석 참고). 실 연동 시 이 파일과
// facilityAssigneeApi.handlers.ts를 걷어내고 실제 배정 가능 검사자 목록 API로 교체한다.
export const mockFacilityAssignableUsers: FacilityAssignableUser[] = [
  { id: 101, name: '김도현 검사자' },
  { id: 102, name: '이서연 검사자' },
  { id: 103, name: '박지훈 관리자' },
];
