import { DEFECT_STATUS_LABEL } from '../types';
import type { DefectStatus } from '../types';

function isDefectStatus(value: string): value is DefectStatus {
  return value in DEFECT_STATUS_LABEL;
}

// ActivityHistoryPanel(하자 단건)과 InspectionActivityPanel(점검 단위 집계, HAJA-393/394)이 동일한
// 변경 이력 문구를 공유하도록 분리했다(원래 ActivityHistoryPanel.tsx 로컬 함수였음).
export function describeDefectChange(
  fieldChanged: string,
  oldValue: string | null,
  newValue: string | null,
): string {
  if (fieldChanged === 'status' && oldValue && newValue && isDefectStatus(oldValue) && isDefectStatus(newValue)) {
    return `상태를 '${DEFECT_STATUS_LABEL[oldValue]}'에서 '${DEFECT_STATUS_LABEL[newValue]}'(으)로 변경했습니다.`;
  }
  return `${fieldChanged} 변경: ${oldValue ?? '-'} → ${newValue ?? '-'}`;
}
