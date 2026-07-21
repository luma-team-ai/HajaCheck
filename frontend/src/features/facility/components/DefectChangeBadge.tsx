import { DEFECT_CHANGE_TYPE_LABEL } from '../constants';
import { DEFECT_CHANGE_TYPE_BADGE_CLASS } from '../facilityDefectColors';
import type { DefectChangeType } from '../types';

type Props = {
  changeType: DefectChangeType;
};

// 하자 변화 목록 배지 — 악화=빨강, 신규=주황, 유지=회색, 조치완료=초록(#489 스펙).
export function DefectChangeBadge({ changeType }: Props) {
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-bold ${DEFECT_CHANGE_TYPE_BADGE_CLASS[changeType]}`}
    >
      <span className="h-1.5 w-1.5 rounded-full bg-current" aria-hidden="true" />
      {DEFECT_CHANGE_TYPE_LABEL[changeType]}
    </span>
  );
}