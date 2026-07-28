import { useEffect, useRef } from 'react';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner/LoadingSpinner';
import { MessageBubble } from './ConversationPanel';
import type { ChatMessageResponse, CounselTicketSummaryResponse } from '../types';

// 상태값 → 과거형 라벨. 실시간 상담(ConversationPanel)과 달리 관리자는 항상 과거 시점을 들여다보는
// 화면이라 "진행중"이 아니라 "대기 중"/"진행 중이었음" 등 정직한 과거형으로 표시한다(계획 §2.4.2).
const ADMIN_STATUS_LABEL: Record<CounselTicketSummaryResponse['status'], string> = {
  WAITING: '대기 중이었음',
  IN_PROGRESS: '진행 중이었음',
  RESOLVED: '종료됨',
  OFFLINE_LEFT: '이탈함',
};

type Props = {
  ticket: CounselTicketSummaryResponse | null;
  messages: ChatMessageResponse[];
  loading: boolean;
  error: string | null;
};

// 플랫폼 관리자 상담 관리(#1168) — 중앙 대화 트랜스크립트 패널. ConversationPanel과 달리 읽기
// 전용이라 입력창(ChatInputBox)·타이핑 인디케이터·연결/종료 버튼이 전혀 없다(회귀 방지 핵심 —
// ReadOnlyConversationPanel.test.tsx에서 이 미렌더를 고정한다). MessageBubble만 재사용해 메시지
// 목록을 그대로 보여준다.
export function ReadOnlyConversationPanel({ ticket, messages, loading, error }: Props) {
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView?.({ block: 'end' });
  }, [messages]);

  if (!ticket) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-3 text-text-muted">
        <p className="m-0 text-sm">좌측에서 상담 세션을 선택하세요.</p>
      </div>
    );
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex items-center justify-between border-b border-border px-6 py-4">
        <div className="flex items-center gap-2">
          <h1 className="m-0 text-lg font-semibold text-primary">{ticket.title}</h1>
          <span className="text-xs text-text-muted">#{ticket.ticketNumber}</span>
        </div>
        <span className="shrink-0 rounded-full bg-surface-sunken px-3 py-1 text-xs font-medium text-text-muted">
          {ADMIN_STATUS_LABEL[ticket.status]}
        </span>
      </div>

      <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto px-6 py-6">
        {loading && <LoadingSpinner className="flex items-center justify-center py-6" />}
        {error && <p className="text-sm text-red-600">{error}</p>}
        {!loading && !error && messages.length === 0 && (
          <p className="text-sm text-text-muted">대화 내용이 없습니다.</p>
        )}
        {!loading &&
          !error &&
          messages.map((message) => <MessageBubble key={message.id} message={message} />)}
        <div ref={bottomRef} />
      </div>
    </div>
  );
}
