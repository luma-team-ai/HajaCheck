import type { DefectStatus } from '../types';

// PR-3에서 확정된 하자 상태 4단계 흐름이다.
export const STEP_LABEL: Record<DefectStatus, string> = {
  DETECTED: '신규',
  CONFIRMED: '검수확정',
  IN_PROGRESS: '조치중',
  RESOLVED: '조치완료',
};

export const NEXT_STATUS: Record<DefectStatus, DefectStatus | null> = {
  DETECTED: 'CONFIRMED',
  CONFIRMED: 'IN_PROGRESS',
  IN_PROGRESS: 'RESOLVED',
  RESOLVED: null,
};

// 역행/건너뛰기 전이 대상(HAJA-349/#630 사유 UI가 "보드 보기" 탭 롤백(#726) 이후 어떤 화면에도
// 연결되지 않던 걸 하자 상세 모달로 재통합하며 정리) — NEXT_STATUS(정방향 1단계)는 DefectActionForm의
// 조치 등록 폼이 이미 처리하므로 여기서는 그 외의 유효 전이만 나열한다. 백엔드 Defect#changeStatus는
// RESOLVED에서의 모든 이탈을 사유 유무와 무관하게 거부하므로(종료 상태) RESOLVED는 출발점에서 제외한다.
// DETECTED도 등급 확정 전에는 changeStatus 자체가 막혀 있어(review() 별도 플로우) 제외한다.
export const REASON_REQUIRED_TARGETS: Partial<Record<DefectStatus, DefectStatus[]>> = {
  CONFIRMED: ['DETECTED', 'RESOLVED'],
  IN_PROGRESS: ['DETECTED', 'CONFIRMED'],
};
