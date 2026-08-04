import { useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner/LoadingSpinner';
import { ConversationPanel } from '../components/ConversationPanel';
import { TicketCard } from '../components/TicketCard';
import { CHAT_BOT_PATH, STATUS_FILTER_TABS } from '../constants';
import { useCounselHistory } from '../hooks/useCounselHistory';
import type { CounselTicketSummaryResponse } from '../types';

// 상담원 콘솔 대기열(useCounselorQueue)의 "상담 중"/"배정 가능" 2-섹션 구성과 결을 맞춘다 —
// 고객 쪽은 "상담 중"/"배정 대기중"으로 명명(#1506 후속 피드백: 배정 전/후를 목록에서부터 구분해
// 달라는 요청). RESOLVED/OFFLINE_LEFT(종료)는 기존처럼 섹션 헤더 없이 flat하게 아래에 둔다 —
// '전체' 탭에서만 섞여 나타나고 '진행중'/'종료' 탭은 어차피 단일 상태군만 보여준다.
function TicketSection({
  label,
  tickets,
  selectedId,
  onSelect,
}: {
  label: string;
  tickets: CounselTicketSummaryResponse[];
  selectedId: number | null;
  onSelect: (id: number) => void;
}) {
  return (
    <div className="flex flex-col gap-2">
      <span className="px-1 text-xs font-semibold text-text-muted">
        {label} ({tickets.length})
      </span>
      {tickets.map((ticket) => (
        <TicketCard
          key={ticket.id}
          ticket={ticket}
          selected={ticket.id === selectedId}
          onSelect={() => onSelect(ticket.id)}
        />
      ))}
    </div>
  );
}

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

  const inProgressTickets = useMemo(() => tickets.filter((t) => t.status === 'IN_PROGRESS'), [tickets]);
  const waitingTickets = useMemo(() => tickets.filter((t) => t.status === 'WAITING'), [tickets]);
  const endedTickets = useMemo(
    () => tickets.filter((t) => t.status === 'RESOLVED' || t.status === 'OFFLINE_LEFT'),
    [tickets],
  );

  function goToNewCounsel() {
    navigate(CHAT_BOT_PATH);
  }

  return (
    // h-full 대신 뷰포트 고정 높이 + overflow-hidden(국소 우회) — AppLayout 루트가 min-h-screen이라
    // h-full은 main의 실제 남은 높이를 보장하지 못하고, 내용이 길면 문서 전체가 늘어나 버려 내부
    // overflow-y-auto가 무력화된다(입력창이 뷰포트 밖으로 밀려나던 원인). 근본 수정은 AppLayout
    // 전역 변경(min-h-screen → h-screen overflow-hidden)이 필요하지만 영향 범위가 앱 전체라
    // 보류하고, 이 페이지만 Header 높이(h-16=4rem)를 뺀 고정 높이로 강제 클리핑한다.
    <div className="flex h-[calc(100vh-4rem)] flex-col overflow-hidden bg-surface-muted p-5">
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
            {!ticketsLoading && !ticketsError && (
              <>
                {waitingTickets.length > 0 && (
                  <TicketSection label="배정 대기중" tickets={waitingTickets} selectedId={selectedId} onSelect={selectTicket} />
                )}
                {inProgressTickets.length > 0 && (
                  <TicketSection label="상담 중" tickets={inProgressTickets} selectedId={selectedId} onSelect={selectTicket} />
                )}
                {endedTickets.length > 0 && (
                  <TicketSection label="종료" tickets={endedTickets} selectedId={selectedId} onSelect={selectTicket} />
                )}
              </>
            )}
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
