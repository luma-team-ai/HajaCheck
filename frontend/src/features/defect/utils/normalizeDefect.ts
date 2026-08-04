import type { Defect, DefectActionResult, DefectStatus } from '../types';

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

// 백엔드 DefectResponse(backend DefectResponse.java)는 actionResult를 중첩 객체가 아니라
// actionPhotoUrl/actionContent/actionDate/actionAssigneeId/actionAssigneeName flat 필드로 응답한다
// (#1182 — 조치 전/후 탭이 실 연동에서 노출되지 않던 원인). MSW mock은 테스트 편의상 이미 중첩된
// actionResult를 그대로 내려주는 경우가 있어, 그 경우는 그대로 통과시키고 flat 필드만 온 경우에만 조립한다.
type RawActionFields = {
  actionPhotoUrl?: string | null;
  actionContent?: string | null;
  actionDate?: string | null;
  actionAssigneeId?: number | null;
  actionAssigneeName?: string | null;
};

function buildActionResult(defect: Defect & RawActionFields): DefectActionResult | null {
  if (defect.actionResult) {
    return defect.actionResult;
  }

  if (defect.actionContent == null) {
    return null;
  }

  return {
    actionContent: defect.actionContent,
    actionDate: defect.actionDate ?? '',
    assigneeId: defect.actionAssigneeId ?? 0,
    assigneeName: defect.actionAssigneeName ?? '',
    afterPhotoUrl: defect.actionPhotoUrl ?? null,
  };
}

export function normalizeDefect<T extends Defect>(
  defect: T & RawActionFields,
): T & { status: DefectStatus; actionResult: DefectActionResult | null } {
  const status = normalizeDefectStatus(defect.status);
  const actionResult = buildActionResult(defect);
  return { ...defect, status, actionResult };
}
