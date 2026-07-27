import type { Defect, DefectStatus } from '../types';

const CURRENT_DEFECT_STATUSES = new Set<string>([
  'DETECTED',
  'CONFIRMED',
  'IN_PROGRESS',
  'RESOLVED',
]);

/**
 * V22 백필 이전에 저장된 ACTION_PENDING은 현재 4단계 모델의 CONFIRMED와 같은 의미다.
 * 배포 시점에 구형 레코드가 남아 있어도 목록/보드에서 조용히 누락되지 않도록 API 경계에서 흡수한다.
 */
export function normalizeDefectStatus(status: unknown): DefectStatus {
  if (status === 'ACTION_PENDING') {
    return 'CONFIRMED';
  }

  if (typeof status === 'string' && CURRENT_DEFECT_STATUSES.has(status)) {
    return status as DefectStatus;
  }

  throw new Error(`지원하지 않는 하자 상태입니다: ${String(status)}`);
}

export function normalizeDefect(defect: Defect): Defect {
  const status = normalizeDefectStatus(defect.status);
  return status === defect.status ? defect : { ...defect, status };
}
