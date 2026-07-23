import { COUNSEL_TYPE_DOT_CLASS, COUNSEL_TYPE_LABEL } from '../statusClasses';
import type { CounselType } from '../types';

type Props = {
  type: CounselType;
};

// 내 상담 내역 테이블 "유형" 열 — 색 점 + 라벨(InspectionStatusBadge와 동일 패턴).
export function CounselTypeBadge({ type }: Props) {
  return (
    <span className="inline-flex items-center gap-1.5 whitespace-nowrap">
      <span
        className={`h-1.5 w-1.5 rounded-full ${COUNSEL_TYPE_DOT_CLASS[type]}`}
        aria-hidden="true"
      />
      {COUNSEL_TYPE_LABEL[type] ?? type}
    </span>
  );
}
