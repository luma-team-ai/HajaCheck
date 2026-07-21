import { deriveDueDateStatus } from '../utils/dueDateStatus';

type Props = {
  nextInspectionDueAt: string | null;
};

const BADGE_CLASS_BY_KIND = {
  overdue: 'bg-[#fee2e2] text-[#dc2626]',
  upcoming: 'bg-[#fef3c7] text-[#b45309]',
  grace: 'bg-[#f4f4f5] text-[#71717a]',
  safe: 'bg-[#dcfce7] text-[#16a34a]',
} as const;

const DOT_CLASS_BY_KIND = {
  overdue: 'bg-[#dc2626]',
  upcoming: 'bg-[#eab308]',
  grace: 'bg-[#a1a1aa]',
  safe: 'bg-[#22c55e]',
} as const;

// 다음점검일 D-day 배지 — facility feature의 InspectionCycleStatusBadge와 동일 팔레트를 쓰는
// feature 로컬 복제(cross-feature import 금지).
export function DueDateBadge({ nextInspectionDueAt }: Props) {
  const status = deriveDueDateStatus(nextInspectionDueAt);

  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-bold ${BADGE_CLASS_BY_KIND[status.kind]}`}
    >
      <span className={`h-1.5 w-1.5 rounded-full ${DOT_CLASS_BY_KIND[status.kind]}`} aria-hidden="true" />
      {status.label}
    </span>
  );
}
