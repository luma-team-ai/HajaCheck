import { useNavigate } from 'react-router-dom';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner/LoadingSpinner';
import { ConversationPanel } from '../components/ConversationPanel';
import { TicketCard } from '../components/TicketCard';
import { CHAT_BOT_PATH, STATUS_FILTER_TABS } from '../constants';
import { useCounselHistory } from '../hooks/useCounselHistory';

// 고객지원 > 내 상담 이력(#20, HAJA-33) — 좌측 목록(상태 필터) + 우측 선택 티켓 대화.
// 앱 셸(AppLayout: SideNavBar+Header+FAB)은 AppShellRoute가 감싸므로 여기서는 카드 본문만 렌더한다.
export function CounselHistoryPage() {
  const navigate = useNavigate();
  const {
    status,
    setStatus,
    tickets,
    ticketsLoading,
    ticketsError,
    selectedId,
    selectTicket,
    selectedTicket,
    messages,
    messagesLoading,
    messagesError,
    sendMessage,
    sendTyping,
    counselorTyping,
    endCounsel,
    ending,
    endError,
  } = useCounselHistory();

  function goToNewCounsel() {
    navigate(CHAT_BOT_PATH);
  }

  return (
    <div className="flex h-full flex-col bg-surface-muted p-5">
      <div className="flex min-h-0 flex-1 overflow-hidden rounded-[20px] border border-border bg-white shadow-[0px_1px_2px_0px_rgba(0,0,0,0.05)]">
        {/* 좌측: 목록 — min-h-0 없으면 이력이 많을 때 이 칼럼이 콘텐츠 높이만큼 늘어나려 해
            부모(overflow-hidden)의 클리핑에 기대게 되고, 우측 채팅창과 스크롤이 뒤섞여 보이는
            원인이 된다(사용자 피드백: "이력 많으면 채팅창도 길어짐") — 명시적으로 고정한다. */}
        <div className="flex min-h-0 w-[320px] shrink-0 flex-col border-r border-border">
          <div className="flex flex-col gap-3 border-b border-border px-5 py-4">
            <h1 className="m-0 text-lg font-semibold text-primary">내 상담 이력 ({tickets.length}건)</h1>
            <div role="tablist" aria-label="상담 이력 상태 필터" className="inline-flex w-fit gap-1 rounded-full bg-surface-muted p-1">
              {STATUS_FILTER_TABS.map((tab) => (
                <button
                  key={tab.value}
                  type="button"
                  role="tab"
                  aria-selected={status === tab.value}
                  onClick={() => setStatus(tab.value)}
                  className={`rounded-full px-4 py-1.5 text-sm font-semibold transition-colors ${
                    status === tab.value ? 'bg-white text-heading shadow-sm' : 'text-text-muted hover:text-text-default'
                  }`}
                >
                  {tab.label}
                </button>
              ))}
            </div>
          </div>

          <div className="flex min-h-0 flex-1 flex-col gap-2 overflow-y-auto p-3">
            {ticketsLoading && <LoadingSpinner className="flex items-center justify-center py-6" />}
            {ticketsError && <p className="px-2 text-sm text-red-600">{ticketsError}</p>}
            {!ticketsLoading && !ticketsError && tickets.length === 0 && (
              <p className="px-2 text-sm text-text-muted">상담 이력이 없습니다.</p>
            )}
            {!ticketsLoading &&
              !ticketsError &&
              tickets.map((ticket) => (
                <TicketCard
                  key={ticket.id}
                  ticket={ticket}
                  selected={ticket.id === selectedId}
                  onSelect={() => selectTicket(ticket.id)}
                />
              ))}
          </div>
        </div>

        {/* 우측: 선택한 티켓의 대화 */}
        <ConversationPanel
          ticket={selectedTicket}
          messages={messages}
          loading={messagesLoading}
          error={messagesError}
          onStartNewCounsel={goToNewCounsel}
          onSendMessage={sendMessage}
          onTyping={sendTyping}
          counselorTyping={counselorTyping}
          onEndCounsel={endCounsel}
          ending={ending}
          endError={endError}
        />
      </div>
    </div>
  );
}
