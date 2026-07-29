import { useState } from 'react';
import { AdminCounselDatePanel } from '../components/AdminCounselDatePanel';
import { ReadOnlyConversationPanel } from '../components/ReadOnlyConversationPanel';
import { AdminCounselInfoPanel } from '../components/AdminCounselInfoPanel';
import { useAdminCounselTicketsByDate } from '../hooks/useAdminCounselTicketsByDate';
import { useAdminCounselTranscript } from '../hooks/useAdminCounselTranscript';
import { useAdminCounselHistoryTranscript } from '../hooks/useAdminCounselHistoryTranscript';
import { getApiErrorMessage } from '../../../shared/api/types';
import type { CounselTicketSummaryResponse } from '../types';

const DEFAULT_PAGE_SIZE = 20;

function todayDateString(): string {
  return new Date().toLocaleDateString('sv-SE'); // YYYY-MM-DD
}

// 플랫폼 관리자 상담 관리(#1168) — 과거 날짜의 상담(고객 지원 채팅) 세션을 조회하는 읽기 전용
// 3단 레이아웃 화면. PlatformAdminUsersPage 패턴을 준용해 페이지가 필터 상태(date/page)를 들고
// 훅 파라미터로 내려주고, 선택된 티켓의 트랜스크립트는 별도 훅으로 조회한다. 라이브 상담 큐(claim/
// 실시간 소켓/메시지 전송)와 성격이 달라 CounselorConsolePage의 컴포넌트를 재사용하지 않고, 계획대로
// 신규 읽기 전용 컴포넌트 3개(AdminCounselDatePanel/ReadOnlyConversationPanel/AdminCounselInfoPanel)로
// 조립한다. 라우팅 상 경로는 /platform-admin/counsels지만, 페이지 파일은 counsel feature 안에 둔다
// (React_코드_컨벤션.md §1 feature 간 직접 import 금지 — 패널·훅·API가 전부 counsel feature 소속이라
// platform-admin/pages에 두면 cross-feature import가 된다. router.tsx는 경로와 무관하게 어느
// feature의 페이지든 lazy import할 수 있어 라우팅 관례상 문제없다).
export function PlatformAdminCounselsPage() {
  const [date, setDate] = useState(todayDateString());
  const [page, setPage] = useState(0);
  const [selectedTicket, setSelectedTicket] = useState<CounselTicketSummaryResponse | null>(null);
  // 우측 정보 패널 "이력" 탭에서 고른 과거 상담 — null이면 selectedTicket(오늘 티켓)의 대화를,
  // 값이 있으면 이 과거 티켓의 대화를 중앙 패널에 보여준다(좌측 선택 상태는 그대로 유지, 사용자 요청).
  const [historyTicket, setHistoryTicket] = useState<CounselTicketSummaryResponse | null>(null);

  const { data, isLoading, isError, error } = useAdminCounselTicketsByDate(date, page, DEFAULT_PAGE_SIZE);
  const tickets = data?.content ?? [];
  const totalElements = data?.totalElements ?? 0;

  const {
    data: messages,
    isLoading: isMessagesLoading,
    isError: isMessagesError,
    error: messagesError,
  } = useAdminCounselTranscript(selectedTicket?.id ?? null);

  const {
    data: historyMessages,
    isLoading: isHistoryMessagesLoading,
    isError: isHistoryMessagesError,
    error: historyMessagesError,
  } = useAdminCounselHistoryTranscript(selectedTicket?.id ?? null, historyTicket?.id ?? null);

  const isHistoryView = historyTicket !== null;

  function handleDateChange(nextDate: string) {
    setDate(nextDate);
    setPage(0);
    setSelectedTicket(null);
    setHistoryTicket(null);
  }

  function handlePageChange(nextPage: number) {
    setPage(nextPage);
    setSelectedTicket(null);
    setHistoryTicket(null);
  }

  function handleSelect(ticket: CounselTicketSummaryResponse) {
    setSelectedTicket(ticket);
    setHistoryTicket(null);
  }

  return (
    <div className="flex min-h-full flex-col p-6 sm:p-8">
      <h1 className="m-0 mb-4 text-2xl font-bold text-heading">상담 관리</h1>
      <div className="flex min-h-0 flex-1 overflow-hidden rounded-[20px] border border-border bg-surface">
        <AdminCounselDatePanel
          date={date}
          onDateChange={handleDateChange}
          tickets={tickets}
          totalElements={totalElements}
          page={page}
          onPageChange={handlePageChange}
          loading={isLoading}
          error={isError ? getApiErrorMessage(error, '상담 목록을 불러오지 못했습니다.') : null}
          selectedTicketId={selectedTicket?.id ?? null}
          onSelect={handleSelect}
        />
        {isHistoryView ? (
          <ReadOnlyConversationPanel
            ticket={historyTicket}
            messages={historyMessages ?? []}
            loading={isHistoryMessagesLoading}
            error={
              isHistoryMessagesError
                ? getApiErrorMessage(historyMessagesError, '대화 내용을 불러오지 못했습니다.')
                : null
            }
            isHistoryView
            onBackFromHistory={() => setHistoryTicket(null)}
          />
        ) : (
          <ReadOnlyConversationPanel
            ticket={selectedTicket}
            messages={messages ?? []}
            loading={isMessagesLoading}
            error={isMessagesError ? getApiErrorMessage(messagesError, '대화 내용을 불러오지 못했습니다.') : null}
          />
        )}
        <AdminCounselInfoPanel
          ticket={selectedTicket}
          selectedHistoryTicketId={historyTicket?.id ?? null}
          onSelectHistory={setHistoryTicket}
        />
      </div>
    </div>
  );
}
