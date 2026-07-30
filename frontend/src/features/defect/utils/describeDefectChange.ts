import { DEFECT_STATUS_LABEL } from '../types';
import type { DefectStatus } from '../types';
import { STATUS_PRESENTATION } from '../constants/defectPresentation';

const LEGACY_DEFECT_STATUS_LABEL: Record<string, string> = {
  ACTION_PENDING: '조치대기',
};

export function getDefectRevisionStatusLabel(value: string | null): string | null {
  if (value == null) {
    return null;
  }
  return DEFECT_STATUS_LABEL[value as DefectStatus] ?? LEGACY_DEFECT_STATUS_LABEL[value] ?? null;
}

export function getDefectRevisionStatusPresentation(
  value: string | null,
): { label: string; className: string } | null {
  const label = getDefectRevisionStatusLabel(value);
  if (label == null) {
    return null;
  }
  if (value != null && value in STATUS_PRESENTATION) {
    return STATUS_PRESENTATION[value as DefectStatus];
  }
  return {
    label,
    className: 'border-amber-200 bg-amber-50 text-amber-500',
  };
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

  switch (fieldChanged) {
    case 'actionMediaId':
      return '조치 사진을 변경했습니다.';
    case 'actionContent':
      return `조치 내용을 '${oldValue ?? '미입력'}'에서 '${newValue ?? '미입력'}'(으)로 수정했습니다.`;
    case 'actionAssigneeId':
      return '조치 담당자를 변경했습니다.';
    case 'actionDate':
      return `조치일을 '${oldValue ?? '미입력'}'에서 '${newValue ?? '미입력'}'(으)로 변경했습니다.`;
    case 'grade':
      return `하자 등급을 '${oldValue ?? '미분류'}'에서 '${newValue ?? '미분류'}'(으)로 변경했습니다.`;
    case 'is_deleted':
      return newValue === 'true' ? '하자를 삭제했습니다.' : '하자를 복원했습니다.';
    default:
      return '하자 정보를 수정했습니다.';
  }
}
