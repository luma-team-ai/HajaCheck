import { INSPECTION_STATUS_DOT_CLASS, INSPECTION_STATUS_LABEL } from '../statusClasses';
import type { InspectionHistoryStatus } from '../types';

type Props = {
  status: InspectionHistoryStatus;
};

// 내 점검 이력 테이블 "상태" 열 — 색 점 + 라벨(dashboard StatusBadge/SeatsSection 상태점과 동일 패턴).
export function InspectionStatusBadge({ status }: Props) {
  return (
    <span className="inline-flex items-center gap-1.5 whitespace-nowrap">
      <span
        className={`h-1.5 w-1.5 rounded-full ${INSPECTION_STATUS_DOT_CLASS[status]}`}
        aria-hidden="true"
      />
      {INSPECTION_STATUS_LABEL[status] ?? status}
    </span>
  );
}
