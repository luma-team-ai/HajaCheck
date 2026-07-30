import { useEffect, useState } from 'react';
import defaultAvatarIcon from '../../../assets/brand/sidenav-default-avatar.svg';
import { counselApi } from '../api/counselApi';
import { ChatAvatar } from '../../../shared/components/ChatAvatar/ChatAvatar';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner/LoadingSpinner';
import { getApiErrorMessage } from '../../../shared/api/types';
import { CATEGORY_LABEL, STATUS_BADGE } from '../constants';
import type { CounselTicketSummaryResponse } from '../types';

type Props = {
  ticket: CounselTicketSummaryResponse | null;
  // 이력 목록에서 과거 상담을 고르면 대화 내용은 이 패널이 아니라 중앙 ReadOnlyConversationPanel이
  // 보여준다(사용자 요청) — 선택 상태·조회는 페이지가 소유하고, 이 패널은 클릭만 위로 알린다.
  selectedHistoryTicketId: number | null;
  onSelectHistory: (ticket: CounselTicketSummaryResponse) => void;
};

type TabKey = 'info' | 'history';

// 플랫폼 관리자 상담 관리(#1168) — 우측 정보 패널. "정보" 탭은 관리자 목록 응답에 채워진
// customerName/Email/Plan/JoinedAt·counselorName을 그대로 보여준다. "이력" 탭은 과거 상담
// 목록 조회(getCustomerHistory)만 담당하고, 대화 조회·표시는 중앙 패널로 위임한다.
export function AdminCounselInfoPanel({ ticket, selectedHistoryTicketId, onSelectHistory }: Props) {
  const [tab, setTab] = useState<TabKey>('info');
  const [history, setHistory] = useState<CounselTicketSummaryResponse[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyError, setHistoryError] = useState<string | null>(null);

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

  return (
    <div className="flex min-h-0 w-72 shrink-0 flex-col border-l border-border bg-surface-muted">
      <div className="flex gap-1 p-4 pb-2">
        <button
          type="button"
          onClick={() => setTab('info')}
          aria-pressed={tab === 'info'}
          className={`flex-1 rounded-full px-3 py-1.5 text-sm font-medium transition-colors ${
            tab === 'info' ? 'bg-white text-point shadow-[0px_1px_2px_0px_rgba(0,0,0,0.08)]' : 'text-text-muted'
          }`}
        >
          정보
        </button>
        <button
          type="button"
          onClick={() => setTab('history')}
          aria-pressed={tab === 'history'}
          className={`flex-1 rounded-full px-3 py-1.5 text-sm font-medium transition-colors ${
            tab === 'history' ? 'bg-white text-point shadow-[0px_1px_2px_0px_rgba(0,0,0,0.08)]' : 'text-text-muted'
          }`}
        >
          이력
        </button>
      </div>

      {!ticket && (
        <p className="px-5 py-4 text-sm text-text-muted">티켓을 선택하면 정보가 표시됩니다.</p>
      )}

      {ticket && tab === 'info' && (
        <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto px-4 pb-4">
          <div className="flex flex-col items-center gap-2 rounded-2xl bg-white px-4 py-5 text-center shadow-[0px_1px_2px_0px_rgba(0,0,0,0.05)]">
            <ChatAvatar icon={defaultAvatarIcon} bgClassName="bg-surface-sunken" className="size-12" />
            <div>
              <p className="m-0 text-sm font-semibold text-primary">{ticket.customerName ?? `고객 #${ticket.userId}`}</p>
              {ticket.customerEmail && <p className="m-0 mt-0.5 text-xs text-text-muted">{ticket.customerEmail}</p>}
            </div>
          </div>

          <div className="flex flex-col gap-2 rounded-2xl bg-white px-4 py-4 text-xs shadow-[0px_1px_2px_0px_rgba(0,0,0,0.05)]">
            <p className="m-0 text-xs font-semibold uppercase tracking-wide text-point">고객 정보</p>
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
            <p className="m-0 text-xs font-semibold uppercase tracking-wide text-point">티켓 정보</p>
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

      {ticket && tab === 'history' && (
        // 목록 자체를 최대 높이(600px)로 제한하고 내부 스크롤 — 이력이 많아도 패널(그리고 3단 레이아웃
        // 전체) 높이가 너무 늘어지지 않게 함(사용자 피드백 반영: 420px -> 600px 완화). 클릭 시 대화는 중앙 패널에서 보여준다.
        <div className="flex max-h-[600px] min-h-0 flex-col gap-2 overflow-y-auto px-4 pb-4">
          {historyLoading && <LoadingSpinner className="flex items-center justify-center py-6" />}
          {historyError && <p className="px-1 text-sm text-red-600">{historyError}</p>}
          {!historyLoading && !historyError && history.length === 0 && (
            <p className="px-1 py-4 text-sm text-text-muted">이 고객의 다른 상담 이력이 없습니다.</p>
          )}
          {!historyLoading &&
            !historyError &&
            history.map((past) => {
              const selected = past.id === selectedHistoryTicketId;
              return (
                <button
                  key={past.id}
                  type="button"
                  onClick={() => onSelectHistory(past)}
                  aria-pressed={selected}
                  className={`flex flex-col gap-1.5 rounded-2xl border bg-white px-4 py-3 text-left text-xs shadow-[0px_1px_2px_0px_rgba(0,0,0,0.05)] transition-colors hover:border-point hover:bg-surface-sunken focus-visible:border-point focus-visible:outline-none ${
                    selected ? 'border-point bg-surface-sunken' : 'border-transparent'
                  }`}
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
                    #{past.ticketNumber} · {STATUS_BADGE[past.status].label}
                  </p>
                </button>
              );
            })}
        </div>
      )}
    </div>
  );
}
