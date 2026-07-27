import { DEFECT_STATUS_LABEL } from '../types';
import type { DefectStatus } from '../types';

const LEGACY_DEFECT_STATUS_LABEL: Record<string, string> = {
  ACTION_PENDING: '조치대기',
};

export function getDefectRevisionStatusLabel(value: string | null): string | null {
  if (value == null) {
    return null;
  }
  return DEFECT_STATUS_LABEL[value as DefectStatus] ?? LEGACY_DEFECT_STATUS_LABEL[value] ?? null;
}

// ActivityHistoryPanel(하자 단건)과 InspectionActivityPanel(점검 단위 집계, HAJA-393/394)이 동일한
// 변경 이력 문구를 공유하도록 분리했다(원래 ActivityHistoryPanel.tsx 로컬 함수였음).
export function describeDefectChange(
  fieldChanged: string,
  oldValue: string | null,
  newValue: string | null,
): string {
  const oldStatusLabel = getDefectRevisionStatusLabel(oldValue);
  const newStatusLabel = getDefectRevisionStatusLabel(newValue);
  if (fieldChanged === 'status' && oldStatusLabel && newStatusLabel) {
    return `상태를 '${oldStatusLabel}'에서 '${newStatusLabel}'(으)로 변경했습니다.`;
  }
  return `${fieldChanged} 변경: ${oldValue ?? '-'} → ${newValue ?? '-'}`;
}
