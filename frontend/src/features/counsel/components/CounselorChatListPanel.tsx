import { useState } from 'react';
import chevronIcon from '../../../assets/brand/sidenav-chevron.svg';
import defaultAvatarIcon from '../../../assets/brand/sidenav-default-avatar.svg';
import { ChatAvatar } from '../../../shared/components/ChatAvatar/ChatAvatar';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner/LoadingSpinner';
import { CATEGORY_LABEL } from '../constants';
import { formatElapsedTime } from '../utils/formatElapsedTime';
import type { CounselTicketSummaryResponse } from '../types';

type Props = {
  tickets: CounselTicketSummaryResponse[];
  loading: boolean;
  error: string | null;
  selectedTicketId: number | null;
  onSelect: (ticket: CounselTicketSummaryResponse) => void;
  conflictMessage: string | null;
  onDismissConflict: () => void;
};

// 상담원 콘솔 마스터-디테일(#1001, HAJA-495) — 좌측 채팅 목록 패널(피그마 시안 기준 w-72).
// 시안은 "이름 · 회사명"/"마지막 메시지 미리보기"/"안읽음 카운트"를 항목마다 보여주지만, 백엔드
// CounselTicketSummaryResponse엔 고객 이름·회사명·마지막 메시지·안읽음 수 필드가 없다(userId만 존재) —
// 없는 데이터를 지어내지 않고, 실제로 있는 필드(title/category/ticketNumber/createdAt)만으로 채운다.
export function CounselorChatListPanel({
  tickets,
  loading,
  error,
  selectedTicketId,
  onSelect,
  conflictMessage,
  onDismissConflict,
}: Props) {
  // 상담원 본인의 온라인 상태 — 백엔드 연동 없는 로컬 UI 토글(브리프 지시대로 최소 구현).
  const [online, setOnline] = useState(true);

  return (
    <div className="flex w-72 shrink-0 flex-col border-r border-border bg-surface-muted">
      <div className="p-4 pb-2">
        <button
          type="button"
          onClick={() => setOnline((prev) => !prev)}
          className="flex w-full items-center justify-between gap-2 rounded-full bg-white px-3 py-2 text-sm font-medium text-primary shadow-[0px_1px_2px_0px_rgba(0,0,0,0.08)]"
        >
          <span className="flex items-center gap-2">
            <span
              className={`size-2 rounded-full ${online ? 'bg-green-500' : 'bg-text-muted'}`}
              aria-hidden="true"
            />
            {online ? '온라인' : '오프라인'}
          </span>
          <img src={chevronIcon} alt="" className="size-3" aria-hidden="true" />
        </button>
      </div>

      <p className="m-0 px-5 pt-2 pb-1 text-xs font-semibold uppercase tracking-wide text-text-muted">
        활성 채팅 ({tickets.length})
      </p>

      {conflictMessage && (
        <div
          role="alert"
          className="mx-4 mt-2 flex items-center justify-between gap-2 rounded-xl border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800"
        >
          <span>{conflictMessage}</span>
          <button
            type="button"
            onClick={onDismissConflict}
            className="shrink-0 font-medium text-amber-700 underline"
          >
            닫기
          </button>
        </div>
      )}

      <div className="flex min-h-0 flex-1 flex-col gap-1 overflow-y-auto px-2 pt-1 pb-4">
        {loading && <LoadingSpinner className="flex items-center justify-center py-6" />}
        {error && <p className="px-3 text-sm text-red-600">{error}</p>}
        {!loading && !error && tickets.length === 0 && (
          <p className="px-3 text-sm text-text-muted">대기 중인 상담 티켓이 없습니다.</p>
        )}
        {!loading &&
          !error &&
          tickets.map((ticket) => {
            const selected = ticket.id === selectedTicketId;
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
                    <p className="m-0 truncate text-sm font-semibold text-primary">{ticket.title}</p>
                    <span className="flex shrink-0 items-center gap-1.5">
                      <span className="rounded-full bg-surface-sunken px-2 py-0.5 text-[11px] font-medium text-text-muted">
                        {CATEGORY_LABEL[ticket.category] ?? ticket.category}
                      </span>
                      <span className="text-[11px] text-text-muted">{formatElapsedTime(ticket.createdAt)}</span>
                    </span>
                  </div>
                  <p className="m-0 truncate text-xs text-text-muted">#{ticket.ticketNumber}</p>
                </div>
              </button>
            );
          })}
      </div>
    </div>
  );
}
