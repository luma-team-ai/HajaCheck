import type { ReportListStatus } from '../types';

// 실 백엔드 ReportStatus는 DRAFT/FINALIZED 2종뿐이다 — Figma 시안의 "제출"/"초안" 등 세분화된
// 상태는 백엔드 모델에 없어(마이그레이션 없이는 도입 불가) 채택하지 않는다(정직한 상태 표시).
const STATUS_CLASSES: Record<ReportListStatus, string> = {
  DRAFT: 'bg-amber-50 text-amber-800',
  FINALIZED: 'bg-green-50 text-green-800',
};

const STATUS_DOT_CLASSES: Record<ReportListStatus, string> = {
  DRAFT: 'bg-amber-500',
  FINALIZED: 'bg-green-500',
};

const STATUS_LABEL: Record<ReportListStatus, string> = {
  DRAFT: '편집 중',
  FINALIZED: '완료',
};

type Props = {
  status: ReportListStatus;
};

export function ReportStatusBadge({ status }: Props) {
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium ${STATUS_CLASSES[status]}`}
    >
      <span className={`h-1.5 w-1.5 rounded-full ${STATUS_DOT_CLASSES[status]}`} />
      {STATUS_LABEL[status]}
    </span>
  );
}
