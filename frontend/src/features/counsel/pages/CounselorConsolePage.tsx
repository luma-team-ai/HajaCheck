import { useEffect, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { CounselorChatListPanel } from '../components/CounselorChatListPanel';
import { CounselorChatWindow } from '../components/CounselorChatWindow';
import { CounselorInfoPanel } from '../components/CounselorInfoPanel';
import { useCounselorQueue } from '../hooks/useCounselorQueue';
import type { CounselTicketDetailResponse, CounselTicketSummaryResponse } from '../types';

type Ticket = CounselTicketSummaryResponse | CounselTicketDetailResponse;

// 상담원 콘솔 마스터-디테일(#1001, HAJA-495) — 대기열(좌측 목록)과 채팅(중앙)·정보(우측)를 한 화면에
// 합친 인박스형 단일 페이지. 이전에는 /counsel-console/queue(목록)와 /counsel-console/tickets/:id
// (채팅)가 완전히 분리된 별도 페이지였으나(CounselorQueuePage/CounselorChatPage), 실제 피그마
// 디자인이 마스터-디테일 레이아웃을 요구해 이 페이지 하나로 통합했다 — 라우트는 그대로 두 경로를
// 유지하되(딥링크 가능), 둘 다 이 컴포넌트를 렌더한다(:id 유무로 selectedTicket만 달라짐).
export function CounselorConsolePage() {
  const { id } = useParams<{ id: string }>();
  const ticketId = id ? Number(id) : null;
  const navigate = useNavigate();
  const location = useLocation();

  const { tickets, loading, error, claim, claimingId, conflictMessage, dismissConflictMessage, reload } =
    useCounselorQueue();

  // 목록에서 클릭 시 navigate state로 넘어오는 티켓 요약 — 새로고침 등으로 없을 수 있어 optional.
  const initialTicket = (location.state as { ticket?: Ticket } | null)?.ticket ?? null;
  const [selectedTicket, setSelectedTicket] = useState<Ticket | null>(initialTicket);

  // URL의 :id가 바뀌었는데(예: 딥링크 직접 진입, 뒤로가기) 그 티켓 정보가 없으면 대기열 목록에서
  // 찾아 보강한다 — 목록에도 없으면(이미 배정되어 WAITING에서 빠진 티켓 등) null 유지, 채팅창이
  // 최소 정보(티켓 번호만)로 표시한다.
  useEffect(() => {
    if (ticketId === null) {
      setSelectedTicket(null);
      return;
    }
    setSelectedTicket((prev) => {
      if (prev?.id === ticketId) return prev;
      return tickets.find((ticket) => ticket.id === ticketId) ?? null;
    });
  }, [ticketId, tickets]);

  function handleSelectTicket(ticket: CounselTicketSummaryResponse) {
    setSelectedTicket(ticket);
    navigate(`/counsel-console/tickets/${ticket.id}`, { state: { ticket } });
  }

  async function handleClaim(ticketToClaim: Ticket) {
    const assigned = await claim(ticketToClaim.id);
    if (assigned) {
      setSelectedTicket(assigned);
      navigate(`/counsel-console/tickets/${assigned.id}`, { state: { ticket: assigned } });
    }
  }

  function handleResolved() {
    setSelectedTicket(null);
    navigate('/counsel-console/queue');
    void reload();
  }

  // 현재 열려 있는 티켓이 대기열(WAITING) 배열에 없으면(막 배정되어 IN_PROGRESS로 전환된 직후 등)
  // 목록 상단에 끼워 넣어 채팅 도중 목록에서 갑자기 사라지는 걸 막는다.
  const displayTickets =
    selectedTicket && !tickets.some((ticket) => ticket.id === selectedTicket.id)
      ? [selectedTicket, ...tickets]
      : tickets;

  return (
    <div className="flex h-full flex-col bg-surface-muted p-5">
      <div className="flex min-h-0 flex-1 overflow-hidden rounded-[20px] border border-border bg-white shadow-[0px_1px_2px_0px_rgba(0,0,0,0.05)]">
        <CounselorChatListPanel
          tickets={displayTickets}
          loading={loading}
          error={error}
          selectedTicketId={selectedTicket?.id ?? ticketId}
          onSelect={handleSelectTicket}
          conflictMessage={conflictMessage}
          onDismissConflict={dismissConflictMessage}
        />
        <CounselorChatWindow
          ticketId={ticketId}
          ticket={selectedTicket}
          claiming={claimingId !== null && claimingId === selectedTicket?.id}
          onClaim={(ticket) => void handleClaim(ticket)}
          onResolved={handleResolved}
        />
        <CounselorInfoPanel ticket={selectedTicket} />
      </div>
    </div>
  );
}
