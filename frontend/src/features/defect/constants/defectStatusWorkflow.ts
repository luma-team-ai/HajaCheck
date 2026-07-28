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
