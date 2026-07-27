import { useEffect, useRef, useState } from 'react';
import counselorIcon from '../../../assets/brand/support-fab-icon.svg';
import defaultAvatarIcon from '../../../assets/brand/sidenav-default-avatar.svg';
import { ChatAvatar } from '../../../shared/components/ChatAvatar/ChatAvatar';
import { ChatInputBox } from '../../../shared/components/ChatInputBox/ChatInputBox';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner/LoadingSpinner';
import { TypingIndicatorBubble } from '../../../shared/components/TypingIndicatorBubble/TypingIndicatorBubble';
import { CATEGORY_LABEL } from '../constants';
import { useCounselorTicketThread } from '../hooks/useCounselorTicketThread';
import type { CounselTicketDetailResponse, CounselTicketSummaryResponse } from '../types';

type Ticket = CounselTicketSummaryResponse | CounselTicketDetailResponse;

type Props = {
  ticketId: number | null;
  ticket: Ticket | null;
  claiming: boolean;
  onClaim: (ticket: Ticket) => void;
  onResolved: () => void;
};

// 상담원 콘솔 마스터-디테일(#1001, HAJA-495) — 중앙 채팅창. 좌측 목록에서 클릭한 티켓을 그대로 연다.
// 대기열(WAITING) 티켓은 아직 담당 상담원이 아니라 GET .../messages 호출 시 백엔드가 403을 낸다
// (CounselTicketService#loadParticipantTicket, 당사자만 허용) — 그래서 WAITING이면 대화창 대신
// "배정받기" CTA만 보여주고, 실제 배정 전까지는 훅(useCounselorTicketThread)을 아예 구독하지 않는다.
export function CounselorChatWindow({ ticketId, ticket, claiming, onClaim, onResolved }: Props) {
  const isWaitingUnclaimed = ticket?.status === 'WAITING';
  const threadTicketId = isWaitingUnclaimed ? null : ticketId;

  const {
    messages,
    messagesLoading,
    messagesError,
    connected,
    sendMessage,
    sendTyping,
    customerTyping,
    resolving,
    resolveError,
    resolve,
  } = useCounselorTicketThread(threadTicketId, onResolved);

  const [draft, setDraft] = useState('');
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    // jsdom(vitest)에는 scrollIntoView가 없어(Element.prototype 미구현) 테스트 환경에서 호출 시
    // TypeError로 렌더 자체가 실패한다 — 실제 브라우저에서만 존재하는 메서드라 방어적으로 호출.
    scrollRef.current?.scrollIntoView?.({ block: 'end' });
  }, [messages, customerTyping]);

  function handleSend() {
    const content = draft.trim();
    if (!content) return;
    sendMessage(content);
    setDraft('');
  }

  function handleDraftChange(value: string) {
    setDraft(value);
    if (value.trim() !== '') sendTyping();
  }

  if (ticketId === null) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-2 text-text-muted">
        <p className="m-0 text-sm">왼쪽 목록에서 상담을 선택하세요.</p>
      </div>
    );
  }

  if (ticket && isWaitingUnclaimed) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-4 text-center">
        <ChatAvatar icon={defaultAvatarIcon} bgClassName="bg-surface-sunken" className="size-12" />
        <div>
          <p className="m-0 text-base font-semibold text-primary">{ticket.title}</p>
          <p className="m-0 mt-1 text-xs text-text-muted">
            {CATEGORY_LABEL[ticket.category] ?? ticket.category} · #{ticket.ticketNumber}
          </p>
        </div>
        <button
          type="button"
          onClick={() => onClaim(ticket)}
          disabled={claiming}
          className="rounded-full bg-primary px-5 py-2.5 text-sm font-semibold text-white disabled:opacity-50"
        >
          {claiming ? '배정 중...' : '상담 배정받기'}
        </button>
      </div>
    );
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex items-center justify-between border-b border-border px-6 py-4">
        <div className="flex items-center gap-3">
          <ChatAvatar icon={defaultAvatarIcon} bgClassName="bg-surface-sunken" />
          <div>
            <div className="flex items-center gap-2">
              <h1 className="m-0 text-base font-semibold text-primary">
                {ticket?.title ?? `상담 티켓 #${ticketId}`}
              </h1>
              {ticket?.ticketNumber && (
                <span className="text-xs text-text-muted">#{ticket.ticketNumber}</span>
              )}
            </div>
            <span className="flex items-center gap-1.5 text-xs font-medium text-info">
              <span
                className={`size-1.5 rounded-full ${connected ? 'bg-info' : 'bg-text-muted'}`}
                aria-hidden="true"
              />
              {connected ? '연결됨' : '연결 중...'}
            </span>
          </div>
        </div>
        <button
          type="button"
          onClick={() => void resolve()}
          disabled={resolving}
          className="shrink-0 rounded-full bg-primary px-4 py-2 text-sm font-semibold text-white disabled:opacity-50"
        >
          {resolving ? '종료 중...' : '상담 종료'}
        </button>
      </div>

      {resolveError && <p className="mx-6 mt-2 text-xs text-red-600">{resolveError}</p>}

      <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto px-6 py-6">
        {messagesLoading && <LoadingSpinner className="flex items-center justify-center py-6" />}
        {messagesError && <p className="text-sm text-red-600">{messagesError}</p>}
        {!messagesLoading && !messagesError && messages.length === 0 && (
          <p className="text-sm text-text-muted">대화 내용이 없습니다.</p>
        )}
        {!messagesLoading &&
          !messagesError &&
          messages.map((message) => {
            const isCounselor = message.sender === 'COUNSELOR';
            return (
              <div
                key={message.id}
                className={`flex items-end gap-2 ${isCounselor ? 'flex-row-reverse' : 'flex-row'}`}
              >
                {!isCounselor && (
                  <ChatAvatar icon={defaultAvatarIcon} bgClassName="bg-surface-sunken" className="mb-1" />
                )}
                {isCounselor && (
                  <ChatAvatar icon={counselorIcon} bgClassName="bg-primary" className="mb-1" />
                )}
                <div className={`flex max-w-[560px] flex-col gap-1 ${isCounselor ? 'items-end' : 'items-start'}`}>
                  <div
                    className={`whitespace-pre-wrap px-5 py-3 text-sm font-medium ${
                      isCounselor
                        ? 'rounded-tl-xl rounded-bl-xl rounded-br-xl bg-primary text-white'
                        : 'rounded-tr-xl rounded-bl-xl rounded-br-xl bg-surface-sunken text-primary'
                    }`}
                  >
                    {message.content}
                  </div>
                  <span className="px-1 text-[11px] text-text-muted">
                    {new Date(message.createdAt).toLocaleTimeString('ko-KR', {
                      hour: '2-digit',
                      minute: '2-digit',
                      hour12: false,
                    })}
                  </span>
                </div>
              </div>
            );
          })}
        {customerTyping && <TypingIndicatorBubble />}
        <div ref={scrollRef} />
      </div>

      <div className="flex justify-center px-6 py-4">
        <ChatInputBox
          value={draft}
          onChange={handleDraftChange}
          onSubmit={handleSend}
          disabled={!connected}
          placeholder="메시지를 입력하세요"
        />
      </div>
    </div>
  );
}
