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
// "진행상태" select가 이미 처리하므로 여기서는 그 외의 유효 전이만 나열한다.
// RESOLVED(#1556, HAJA-26 3차)는 더 이상 이탈 불가한 종료 상태가 아니다 — 백엔드가 사유와 함께
// IN_PROGRESS로 되돌리는 것을 허용하므로 출발점에 포함한다.
// DETECTED는 등급 확정 전에는 changeStatus 자체가 막혀 있어(review() 별도 플로우) 제외한다.
// CONFIRMED→RESOLVED(#1642) — 조치결과등록(PATCH /defects/{id}/action) 없이 사유만으로 조치완료까지
// 직행하면 actionContent/조치 사진이 비어 defect_action_logs가 기록되지 않는다(하자상세 조치내용 미노출
// 버그의 원인). RESOLVED 도달은 이제 조치결과등록 폼으로만 가능하도록 여기서 제거한다 — 역행 전이
// (RESOLVED→IN_PROGRESS, IN_PROGRESS/CONFIRMED→DETECTED 등)는 그대로 유지.
export const REASON_REQUIRED_TARGETS: Partial<Record<DefectStatus, DefectStatus[]>> = {
  CONFIRMED: ['DETECTED'],
  IN_PROGRESS: ['DETECTED', 'CONFIRMED'],
  RESOLVED: ['IN_PROGRESS'],
};
