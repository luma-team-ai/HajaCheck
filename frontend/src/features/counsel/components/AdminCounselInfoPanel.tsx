import { useEffect, useState } from 'react';
import backIcon from '../../../assets/brand/sidenav-chevron.svg';
import defaultAvatarIcon from '../../../assets/brand/sidenav-default-avatar.svg';
import { counselApi } from '../api/counselApi';
import { ChatAvatar } from '../../../shared/components/ChatAvatar/ChatAvatar';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner/LoadingSpinner';
import { getApiErrorMessage } from '../../../shared/api/types';
import { CATEGORY_LABEL } from '../constants';
import { MessageBubble } from './ConversationPanel';
import type { ChatMessageResponse, CounselTicketSummaryResponse } from '../types';

type Props = {
  ticket: CounselTicketSummaryResponse | null;
};

type TabKey = 'info' | 'history';

// 플랫폼 관리자 상담 관리(#1168) — 우측 정보 패널. 이력 드릴다운 로직은
// CounselorInfoPanel.tsx의 "이력" 탭을 그대로 이식(getCustomerHistory/getCustomerHistoryMessages는
// 이미 PLATFORM_ADMIN 우회를 지원). "정보" 탭만 다르다 — 상담원 콘솔은 고객 이름/이메일이 없어
// "고객 #{userId}"만 표시했지만, 관리자 목록 응답엔 customerName/Email/Plan/JoinedAt이 채워져
// 있으므로 실제 프로필로 보여주고, 담당 상담원(counselorName)도 신규로 노출한다.
export function AdminCounselInfoPanel({ ticket }: Props) {
  const [tab, setTab] = useState<TabKey>('info');
  const [history, setHistory] = useState<CounselTicketSummaryResponse[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyError, setHistoryError] = useState<string | null>(null);

  const [selectedHistoryTicket, setSelectedHistoryTicket] = useState<CounselTicketSummaryResponse | null>(null);
  const [historyMessages, setHistoryMessages] = useState<ChatMessageResponse[]>([]);
  const [historyMessagesLoading, setHistoryMessagesLoading] = useState(false);
  const [historyMessagesError, setHistoryMessagesError] = useState<string | null>(null);

  useEffect(() => {
    if (tab !== 'history' || !ticket) {
      return;
    }
    let cancelled = false;
    setHistoryLoading(true);
    setHistoryError(null);
    counselApi
      .getCustomerHistory(ticket.id)
      .then((res) => {
        if (!cancelled) setHistory(res.data);
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setHistoryError(getApiErrorMessage(err, '상담 이력을 불러오지 못했습니다.'));
          setHistory([]);
        }
      })
      .finally(() => {
        if (!cancelled) setHistoryLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [tab, ticket?.id]);

  useEffect(() => {
    setSelectedHistoryTicket(null);
  }, [ticket?.id]);

  useEffect(() => {
    if (!ticket || !selectedHistoryTicket) {
      return;
    }
    let cancelled = false;
    setHistoryMessagesLoading(true);
    setHistoryMessagesError(null);
    counselApi
      .getCustomerHistoryMessages(ticket.id, selectedHistoryTicket.id)
      .then((res) => {
        if (!cancelled) setHistoryMessages(res.data);
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setHistoryMessagesError(getApiErrorMessage(err, '대화 내용을 불러오지 못했습니다.'));
          setHistoryMessages([]);
        }
      })
      .finally(() => {
        if (!cancelled) setHistoryMessagesLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [ticket?.id, selectedHistoryTicket?.id]);

  return (
    <div className="flex w-72 shrink-0 flex-col border-l border-border bg-surface-muted">
      <div className="flex gap-1 p-4 pb-2">
        <button
          type="button"
          onClick={() => setTab('info')}
          aria-pressed={tab === 'info'}
          className={`flex-1 rounded-full px-3 py-1.5 text-sm font-medium transition-colors ${
            tab === 'info' ? 'bg-white text-primary shadow-[0px_1px_2px_0px_rgba(0,0,0,0.08)]' : 'text-text-muted'
          }`}
        >
          정보
        </button>
        <button
          type="button"
          onClick={() => setTab('history')}
          aria-pressed={tab === 'history'}
          className={`flex-1 rounded-full px-3 py-1.5 text-sm font-medium transition-colors ${
            tab === 'history' ? 'bg-white text-primary shadow-[0px_1px_2px_0px_rgba(0,0,0,0.08)]' : 'text-text-muted'
          }`}
        >
          이력
        </button>
      </div>

      {!ticket && (
        <p className="px-5 py-4 text-sm text-text-muted">티켓을 선택하면 정보가 표시됩니다.</p>
      )}

      {ticket && tab === 'info' && (
        <div className="flex flex-col gap-4 overflow-y-auto px-4 pb-4">
          <div className="flex flex-col items-center gap-2 rounded-2xl bg-white px-4 py-5 text-center shadow-[0px_1px_2px_0px_rgba(0,0,0,0.05)]">
            <ChatAvatar icon={defaultAvatarIcon} bgClassName="bg-surface-sunken" className="size-12" />
            <div>
              <p className="m-0 text-sm font-semibold text-primary">{ticket.customerName ?? `고객 #${ticket.userId}`}</p>
              {ticket.customerEmail && <p className="m-0 mt-0.5 text-xs text-text-muted">{ticket.customerEmail}</p>}
            </div>
          </div>

          <div className="flex flex-col gap-2 rounded-2xl bg-white px-4 py-4 text-xs shadow-[0px_1px_2px_0px_rgba(0,0,0,0.05)]">
            <p className="m-0 text-xs font-semibold uppercase tracking-wide text-text-muted">고객 정보</p>
            <dl className="m-0 flex flex-col gap-1.5">
              <div className="flex justify-between gap-2">
                <dt className="text-text-muted">플랜</dt>
                <dd className="m-0 font-medium text-primary">{ticket.customerPlan ?? '-'}</dd>
              </div>
              <div className="flex justify-between gap-2">
                <dt className="text-text-muted">가입일</dt>
                <dd className="m-0 font-medium text-primary">
                  {ticket.customerJoinedAt
                    ? new Date(ticket.customerJoinedAt).toLocaleDateString('sv-SE').replaceAll('-', '.')
                    : '-'}
                </dd>
              </div>
            </dl>
          </div>

          <div className="flex flex-col gap-2 rounded-2xl bg-white px-4 py-4 text-xs shadow-[0px_1px_2px_0px_rgba(0,0,0,0.05)]">
            <p className="m-0 text-xs font-semibold uppercase tracking-wide text-text-muted">티켓 정보</p>
            <dl className="m-0 flex flex-col gap-1.5">
              <div className="flex justify-between gap-2">
                <dt className="text-text-muted">티켓 번호</dt>
                <dd className="m-0 font-medium text-primary">{ticket.ticketNumber}</dd>
              </div>
              <div className="flex justify-between gap-2">
                <dt className="text-text-muted">카테고리</dt>
                <dd className="m-0 truncate font-medium text-primary">
                  {CATEGORY_LABEL[ticket.category] ?? ticket.category}
                </dd>
              </div>
              <div className="flex justify-between gap-2">
                <dt className="text-text-muted">접수일</dt>
                <dd className="m-0 font-medium text-primary">
                  {new Date(ticket.createdAt).toLocaleDateString('sv-SE').replaceAll('-', '.')}
                </dd>
              </div>
              <div className="flex justify-between gap-2">
                <dt className="text-text-muted">담당 상담원</dt>
                <dd className="m-0 font-medium text-primary">{ticket.counselorName ?? '미배정'}</dd>
              </div>
            </dl>
          </div>
        </div>
      )}

      {ticket && tab === 'history' && !selectedHistoryTicket && (
        <div className="flex flex-col gap-2 overflow-y-auto px-4 pb-4">
          {historyLoading && <LoadingSpinner className="flex items-center justify-center py-6" />}
          {historyError && <p className="px-1 text-sm text-red-600">{historyError}</p>}
          {!historyLoading && !historyError && history.length === 0 && (
            <p className="px-1 py-4 text-sm text-text-muted">이 고객의 다른 상담 이력이 없습니다.</p>
          )}
          {!historyLoading &&
            !historyError &&
            history.map((past) => (
              <button
                key={past.id}
                type="button"
                onClick={() => setSelectedHistoryTicket(past)}
                className="flex flex-col gap-1.5 rounded-2xl border border-transparent bg-white px-4 py-3 text-left text-xs shadow-[0px_1px_2px_0px_rgba(0,0,0,0.05)] transition-colors hover:border-point hover:bg-surface-sunken focus-visible:border-point focus-visible:outline-none"
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="shrink-0 rounded-full bg-surface-sunken px-2 py-0.5 text-[11px] font-medium text-text-muted">
                    {CATEGORY_LABEL[past.category] ?? past.category}
                  </span>
                  <span className="shrink-0 text-[11px] text-text-muted">
                    {new Date(past.createdAt).toLocaleDateString('sv-SE').replaceAll('-', '.')}
                  </span>
                </div>
                <p className="m-0 truncate text-sm font-semibold text-primary">{past.title}</p>
                <p className="m-0 truncate text-text-muted">
                  #{past.ticketNumber} · {past.status}
                </p>
              </button>
            ))}
        </div>
      )}

      {ticket && tab === 'history' && selectedHistoryTicket && (
        <div className="flex min-h-0 flex-1 flex-col">
          <div className="flex items-center gap-2 px-4 pb-2">
            <button
              type="button"
              onClick={() => setSelectedHistoryTicket(null)}
              className="flex items-center gap-1 rounded-full px-2 py-1 text-xs font-medium text-text-muted hover:bg-white"
            >
              <img src={backIcon} alt="" className="size-3 rotate-90" aria-hidden="true" />
              목록으로
            </button>
          </div>
          <div className="px-4 pb-2">
            <p className="m-0 truncate text-sm font-semibold text-primary">{selectedHistoryTicket.title}</p>
            <p className="m-0 truncate text-xs text-text-muted">
              {selectedHistoryTicket.category} · #{selectedHistoryTicket.ticketNumber}
            </p>
          </div>
          <div className="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto px-4 pb-4">
            {historyMessagesLoading && <LoadingSpinner className="flex items-center justify-center py-6" />}
            {historyMessagesError && <p className="text-sm text-red-600">{historyMessagesError}</p>}
            {!historyMessagesLoading && !historyMessagesError && historyMessages.length === 0 && (
              <p className="text-sm text-text-muted">대화 내용이 없습니다.</p>
            )}
            {!historyMessagesLoading &&
              !historyMessagesError &&
              historyMessages.map((message) => <MessageBubble key={message.id} message={message} />)}
          </div>
        </div>
      )}
    </div>
  );
}
