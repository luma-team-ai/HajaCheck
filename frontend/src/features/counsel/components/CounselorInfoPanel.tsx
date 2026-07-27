import { useEffect, useState } from 'react';
import backIcon from '../../../assets/brand/sidenav-chevron.svg';
import defaultAvatarIcon from '../../../assets/brand/sidenav-default-avatar.svg';
import { counselApi } from '../api/counselApi';
import { ChatAvatar } from '../../../shared/components/ChatAvatar/ChatAvatar';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner/LoadingSpinner';
import { getApiErrorMessage } from '../../../shared/api/types';
import { MessageBubble } from './ConversationPanel';
import type { ChatMessageResponse, CounselTicketDetailResponse, CounselTicketSummaryResponse } from '../types';

type Ticket = CounselTicketSummaryResponse | CounselTicketDetailResponse;

type Props = {
  ticket: Ticket | null;
};

type TabKey = 'info' | 'history';

// 상담원 콘솔 마스터-디테일(#1001, HAJA-495) — 우측 정보 패널.
// 원 디자인엔 "상담 태그"/"비공개 메모 저장"/"매크로 안내 박스"가 있으나, 백엔드에 그 기능이 전혀
// 없다(CounselTicketController 확인 — 태그·메모 저장 API 없음, 매크로 기능 없음) — 동작하지 않는
// 가짜 UI를 만들지 않기 위해 해당 섹션은 생략한다. 고객 상담 이력은 GET .../customer-history(#1001
// 후속)로 실제 연동한다.
export function CounselorInfoPanel({ ticket }: Props) {
  const [tab, setTab] = useState<TabKey>('info');
  const [history, setHistory] = useState<CounselTicketSummaryResponse[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyError, setHistoryError] = useState<string | null>(null);

  // 이력 드릴다운(#1001 후속) — 목록 항목 클릭 시 그 티켓의 대화를 같은 패널에서 보여주고,
  // "목록으로" 버튼으로 되돌아간다(페이지 이동 없이 실시간 채팅 화면을 방해하지 않는다).
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
    // ticket.id 변경 시(다른 티켓 선택) 재조회 — tab을 'history'로 유지한 채 티켓만 바뀌는 경우 포함.
  }, [tab, ticket?.id]);

  // 티켓 전환 시 드릴다운 상태를 초기화 — 다른 상담으로 넘어갔는데 이전 티켓의 이력 상세가 남아있으면 혼란스럽다.
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
          정보/메모
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
          {/* 고객 프로필 — 백엔드에 고객 이름·회사·직함 필드가 없어(userId만 존재) 실제로 있는
              필드(고객 ID·티켓 정보)만 표시한다. */}
          <div className="flex flex-col items-center gap-2 rounded-2xl bg-white px-4 py-5 text-center shadow-[0px_1px_2px_0px_rgba(0,0,0,0.05)]">
            <ChatAvatar icon={defaultAvatarIcon} bgClassName="bg-surface-sunken" className="size-12" />
            <div>
              <p className="m-0 text-sm font-semibold text-primary">고객 #{ticket.userId}</p>
              <p className="m-0 mt-0.5 text-xs text-text-muted">{ticket.category}</p>
            </div>
          </div>

          <div className="flex flex-col gap-2 rounded-2xl bg-white px-4 py-4 text-xs shadow-[0px_1px_2px_0px_rgba(0,0,0,0.05)]">
            <p className="m-0 text-xs font-semibold uppercase tracking-wide text-text-muted">티켓 정보</p>
            <dl className="m-0 flex flex-col gap-1.5">
              <div className="flex justify-between gap-2">
                <dt className="text-text-muted">티켓 번호</dt>
                <dd className="m-0 font-medium text-primary">{ticket.ticketNumber}</dd>
              </div>
              <div className="flex justify-between gap-2">
                <dt className="text-text-muted">제목</dt>
                <dd className="m-0 truncate font-medium text-primary">{ticket.title}</dd>
              </div>
              <div className="flex justify-between gap-2">
                <dt className="text-text-muted">접수일</dt>
                <dd className="m-0 font-medium text-primary">
                  {new Date(ticket.createdAt).toLocaleDateString('sv-SE').replaceAll('-', '.')}
                </dd>
              </div>
            </dl>
          </div>

          {/* 상담 태그(types.ts에 tag 관련 필드 없음)·비공개 메모 저장(저장 API 없음) 섹션은
              실제 기능이 없어 생략 — 가짜 저장 버튼을 두지 않는다. */}
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
                className="group flex flex-col gap-1 rounded-2xl border border-transparent bg-white px-4 py-3 text-left text-xs shadow-[0px_1px_2px_0px_rgba(0,0,0,0.05)] transition-colors hover:border-point hover:bg-surface-sunken focus-visible:border-point focus-visible:outline-none"
              >
                <div className="flex items-center justify-between gap-2">
                  <p className="m-0 line-clamp-1 text-sm font-semibold text-primary group-hover:line-clamp-none">
                    {past.title}
                  </p>
                  <span className="shrink-0 text-[11px] text-text-muted">
                    {new Date(past.createdAt).toLocaleDateString('sv-SE').replaceAll('-', '.')}
                  </span>
                </div>
                {/* 실제 마지막 메시지 미리보기 필드는 백엔드에 없다(CounselTicketSummaryResponse에
                    lastMessage 없음) — 지어내지 않고, 대신 호버 시 잘려있던 제목/카테고리를
                    그대로 펼쳐 보여주는 것으로 "내용을 좀 더 보여달라"는 요청에 대응한다. */}
                <p className="m-0 line-clamp-1 text-text-muted group-hover:line-clamp-none">
                  {past.category} · #{past.ticketNumber} · {past.status}
                </p>
              </button>
            ))}
        </div>
      )}

      {/* 이력 드릴다운 상세 — 클릭한 과거 티켓의 실제 대화 내용(#1001 후속). */}
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

      {/* 원 디자인의 매크로 안내("/답변 명령어로...") 박스는 매크로 기능 자체가 없어 생략. */}
    </div>
  );
}
