import { useState } from 'react';
import defaultAvatarIcon from '../../../assets/brand/sidenav-default-avatar.svg';
import { ChatAvatar } from '../../../shared/components/ChatAvatar/ChatAvatar';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner/LoadingSpinner';
import { Pagination } from '../../../shared/components/Pagination/Pagination';
import { CATEGORY_LABEL, DEFAULT_PAGE_SIZE, STATUS_BADGE } from '../constants';
import type { CounselTicketStatus, CounselTicketSummaryResponse } from '../types';

type Props = {
  date: string;
  onDateChange: (date: string) => void;
  tickets: CounselTicketSummaryResponse[];
  totalElements: number;
  page: number;
  onPageChange: (page: number) => void;
  loading: boolean;
  error: string | null;
  selectedTicketId: number | null;
  onSelect: (ticket: CounselTicketSummaryResponse) => void;
};

// 상태별 3분류 — WAITING(대기)을 진행중과 분리해 관리자가 대기 중인 고객을 바로 구분할 수 있게 한다
// (사용자 요청). RESOLVED/OFFLINE_LEFT는 기존 STATUS_BADGE와 동일하게 "종료" 하나로 묶는다.
const GROUPS: {
  key: string;
  label: string;
  dotClassName: string;
  match: (status: CounselTicketStatus) => boolean;
}[] = [
  { key: 'waiting', label: '상담 대기', dotClassName: 'bg-amber-500', match: (s) => s === 'WAITING' },
  { key: 'in-progress', label: '진행중', dotClassName: 'bg-point', match: (s) => s === 'IN_PROGRESS' },
  {
    key: 'ended',
    label: '종료',
    dotClassName: 'bg-zinc-400',
    match: (s) => s === 'RESOLVED' || s === 'OFFLINE_LEFT',
  },
];

// 카드 배지 전용 — STATUS_BADGE는 WAITING을 IN_PROGRESS와 동일한 "진행중"으로 묶지만(상담원 콘솔 전용
// 의미), 이 패널은 WAITING을 "상담 대기" 그룹으로 별도 분리했으므로 카드에도 "진행중"이 아닌 "대기"를
// 표시해야 그룹 취지와 어긋나지 않는다(PR머신 리뷰 P2).
function getCardBadge(status: CounselTicketStatus) {
  if (status === 'WAITING') {
    return { label: '대기', dotClassName: 'bg-amber-500', textClassName: 'text-amber-600' };
  }
  return STATUS_BADGE[status];
}

// 플랫폼 관리자 상담 관리(#1168) — 좌측 날짜별 세션 목록 패널. 라이브 큐(CounselorChatListPanel)와
// 달리 온라인 토글이 없다(읽기 전용 조회 화면이라 상담원 상태 개념 자체가 없음). 날짜는 네이티브
// <input type="date">(ErrorLogFilterBar.tsx 컨벤션)로 고르고, 그 날짜의 접수(createdAt) 기준 티켓
// 목록만 보여준다(종료일 endedAt 아님 — 계획 확정 사항).
export function AdminCounselDatePanel({
  date,
  onDateChange,
  tickets,
  totalElements,
  page,
  onPageChange,
  loading,
  error,
  selectedTicketId,
  onSelect,
}: Props) {
  const totalPages = Math.max(1, Math.ceil(totalElements / DEFAULT_PAGE_SIZE));
  const today = new Date().toLocaleDateString('sv-SE');

  const [openGroupKey, setOpenGroupKey] = useState<string | null>('waiting');

  const toggleGroup = (key: string) => {
    setOpenGroupKey((prevKey) => (prevKey === key ? null : key));
  };

  function renderTicketCard(ticket: CounselTicketSummaryResponse) {
    const selected = ticket.id === selectedTicketId;
    const badge = getCardBadge(ticket.status);
    return (
      <button
        key={ticket.id}
        type="button"
        onClick={() => onSelect(ticket)}
        aria-pressed={selected}
        className={`flex items-center gap-3 rounded-2xl border px-3 py-2.5 text-left transition-colors ${
          selected
            ? 'border-point bg-surface-sunken shadow-[0px_1px_2px_0px_rgba(0,0,0,0.08)]'
            : 'border-border/60 bg-white/40 opacity-70 hover:border-point/40 hover:bg-white/80 hover:opacity-100'
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
  }

  return (
    <div className="flex min-h-0 w-72 shrink-0 flex-col border-r border-border bg-surface-muted">
      <div className="p-4 pb-2">
        <input
          type="date"
          aria-label="날짜 검색"
          value={date}
          max={today}
          onChange={(event) => onDateChange(event.target.value)}
          className="h-9 w-full rounded-lg border border-border bg-white px-3 text-[13px] text-text-default focus:border-point focus:outline-none focus:ring-1 focus:ring-point"
        />
      </div>

      <p className="m-0 px-5 pt-2 pb-1 text-xs font-semibold uppercase tracking-wide text-text-muted">
        상담 세션 ({tickets.length})
      </p>

      <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto px-2 pt-2 pb-4">
        {loading && <LoadingSpinner className="flex items-center justify-center py-6" />}
        {error && <p className="px-3 text-sm text-red-600">{error}</p>}
        {!loading && !error && tickets.length === 0 && (
          <p className="px-3 text-sm text-text-muted">선택한 날짜에 상담 티켓이 없습니다.</p>
        )}
        {!loading &&
          !error &&
          GROUPS.map((group) => {
            const groupTickets = tickets.filter((ticket) => group.match(ticket.status));
            if (groupTickets.length === 0) {
              return null;
            }
            const isOpen = group.key === openGroupKey;
            return (
              <div
                key={group.key}
                className="flex min-h-0 flex-col gap-2 rounded-2xl border border-border/80 bg-white/40 p-3 shadow-[0px_1px_2px_0px_rgba(0,0,0,0.02)]"
              >
                <button
                  type="button"
                  onClick={() => toggleGroup(group.key)}
                  className="flex w-full items-center justify-between px-1 py-0.5 hover:opacity-80"
                >
                  <div className="flex items-center gap-1.5">
                    <span className={`size-1.5 rounded-full ${group.dotClassName}`} aria-hidden="true" />
                    <p className="m-0 text-xs font-semibold text-text-muted">
                      {group.label} ({groupTickets.length})
                    </p>
                  </div>
                  <svg
                    className={`size-3.5 text-text-muted transition-transform duration-200 ${
                      isOpen ? 'rotate-180' : ''
                    }`}
                    fill="none"
                    viewBox="0 0 24 24"
                    stroke="currentColor"
                    strokeWidth={2.5}
                  >
                    <path strokeLinecap="round" strokeLinejoin="round" d="M19 9l-7 7-7-7" />
                  </svg>
                </button>
                {/* 그룹당 최대 6~7건 노출 후 내부 스크롤 — 단일 아코디언이므로 한도를 넉넉하게 완화(max-h-64 -> max-h-[520px]) */}
                {isOpen && (
                  <div className="flex max-h-[520px] flex-col gap-2 overflow-y-auto pr-1">
                    {groupTickets.map(renderTicketCard)}
                  </div>
                )}
              </div>
            );
          })}
      </div>

      {/* 하루 티켓이 DEFAULT_PAGE_SIZE(20)를 넘으면 21건째부터 조회 불가하던 문제 대응(PR머신 리뷰 P3) */}
      {!loading && !error && totalPages > 1 && (
        <div className="flex justify-center border-t border-border p-3">
          <Pagination currentPage={page + 1} totalPages={totalPages} onPageChange={(p) => onPageChange(p - 1)} />
        </div>
      )}
    </div>
  );
}
