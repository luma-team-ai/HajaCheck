import { STATUS_BADGE } from '../constants';
import type { CounselTicketSummaryResponse } from '../types';

// 오늘 티켓은 시각(HH:mm), 그 외는 날짜(YYYY.MM.DD) — Figma: 최신 티켓만 시각 표시.
function formatCardTimestamp(iso: string): string {
  const date = new Date(iso);
  const now = new Date();
  const isToday =
    date.getFullYear() === now.getFullYear() &&
    date.getMonth() === now.getMonth() &&
    date.getDate() === now.getDate();
  if (isToday) {
    return date.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', hour12: false });
  }
  return date.toLocaleDateString('sv-SE').replaceAll('-', '.'); // sv-SE 로케일이 YYYY-MM-DD를 보장
}

type Props = {
  ticket: CounselTicketSummaryResponse;
  selected: boolean;
  onSelect: () => void;
};

export function TicketCard({ ticket, selected, onSelect }: Props) {
  const badge = STATUS_BADGE[ticket.status];

  return (
    <button
      type="button"
      onClick={onSelect}
      aria-pressed={selected}
      className={`flex w-full flex-col gap-1.5 rounded-2xl border bg-white px-4 py-3 text-left shadow-[0px_1px_2px_0px_rgba(0,0,0,0.05)] transition-colors ${
        selected ? 'border-primary' : 'border-border hover:bg-surface-sunken'
      }`}
    >
      <div className="flex items-center justify-between gap-2">
        <span className="rounded-full bg-surface-sunken px-2.5 py-0.5 text-xs font-medium text-text-muted">
          {ticket.category}
        </span>
        <span className={`flex items-center gap-1 text-xs font-medium ${badge.textClassName}`}>
          <span className={`size-1.5 rounded-full ${badge.dotClassName}`} aria-hidden="true" />
          {badge.label}
        </span>
      </div>
      <p className="m-0 truncate text-sm font-semibold text-primary">{ticket.title}</p>
      <p className="m-0 truncate text-xs text-text-muted">
        {ticket.counselorName ?? '배정 대기 중'} · {formatCardTimestamp(ticket.createdAt)}
      </p>
    </button>
  );
}
