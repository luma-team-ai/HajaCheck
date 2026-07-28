import defaultAvatarIcon from '../../../assets/brand/sidenav-default-avatar.svg';
import { ChatAvatar } from '../../../shared/components/ChatAvatar/ChatAvatar';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner/LoadingSpinner';
import { CATEGORY_LABEL, STATUS_BADGE } from '../constants';
import type { CounselTicketSummaryResponse } from '../types';

type Props = {
  date: string;
  onDateChange: (date: string) => void;
  tickets: CounselTicketSummaryResponse[];
  loading: boolean;
  error: string | null;
  selectedTicketId: number | null;
  onSelect: (ticket: CounselTicketSummaryResponse) => void;
};

// 플랫폼 관리자 상담 관리(#1168) — 좌측 날짜별 세션 목록 패널. 라이브 큐(CounselorChatListPanel)와
// 달리 온라인 토글이 없다(읽기 전용 조회 화면이라 상담원 상태 개념 자체가 없음). 날짜는 네이티브
// <input type="date">(ErrorLogFilterBar.tsx 컨벤션)로 고르고, 그 날짜의 접수(createdAt) 기준 티켓
// 목록만 보여준다(종료일 endedAt 아님 — 계획 확정 사항).
export function AdminCounselDatePanel({
  date,
  onDateChange,
  tickets,
  loading,
  error,
  selectedTicketId,
  onSelect,
}: Props) {
  return (
    <div className="flex w-72 shrink-0 flex-col border-r border-border bg-surface-muted">
      <div className="p-4 pb-2">
        <input
          type="date"
          aria-label="날짜 검색"
          value={date}
          onChange={(event) => onDateChange(event.target.value)}
          className="h-9 w-full rounded-lg border border-border bg-white px-3 text-[13px] text-text-default"
        />
      </div>

      <p className="m-0 px-5 pt-2 pb-1 text-xs font-semibold uppercase tracking-wide text-text-muted">
        상담 세션 ({tickets.length})
      </p>

      <div className="flex min-h-0 flex-1 flex-col gap-1 overflow-y-auto px-2 pt-1 pb-4">
        {loading && <LoadingSpinner className="flex items-center justify-center py-6" />}
        {error && <p className="px-3 text-sm text-red-600">{error}</p>}
        {!loading && !error && tickets.length === 0 && (
          <p className="px-3 text-sm text-text-muted">선택한 날짜에 상담 티켓이 없습니다.</p>
        )}
        {!loading &&
          !error &&
          tickets.map((ticket) => {
            const selected = ticket.id === selectedTicketId;
            const badge = STATUS_BADGE[ticket.status];
            return (
              <button
                key={ticket.id}
                type="button"
                onClick={() => onSelect(ticket)}
                aria-pressed={selected}
                className={`flex items-center gap-3 rounded-2xl px-3 py-2.5 text-left transition-colors ${
                  selected
                    ? 'bg-white shadow-[0px_1px_2px_0px_rgba(0,0,0,0.08)] outline outline-1 outline-primary'
                    : 'opacity-70 hover:bg-white/60'
                }`}
              >
                <ChatAvatar icon={defaultAvatarIcon} bgClassName="bg-surface-sunken" />
                <div className="min-w-0 flex-1">
                  <div className="flex items-center justify-between gap-2">
                    <p className="m-0 truncate text-sm font-semibold text-primary">
                      {ticket.customerName ?? `고객 #${ticket.userId}`}
                    </p>
                    <span className="flex shrink-0 items-center gap-1 text-[11px] font-medium">
                      <span className={`size-1.5 rounded-full ${badge.dotClassName}`} aria-hidden="true" />
                      <span className={badge.textClassName}>{badge.label}</span>
                    </span>
                  </div>
                  <p className="m-0 truncate text-xs text-text-muted">
                    {CATEGORY_LABEL[ticket.category] ?? ticket.category} · {ticket.title}
                  </p>
                  <p className="m-0 truncate text-xs text-text-muted">#{ticket.ticketNumber}</p>
                </div>
              </button>
            );
          })}
      </div>
    </div>
  );
}
