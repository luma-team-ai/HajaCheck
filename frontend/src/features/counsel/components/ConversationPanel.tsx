import { useState } from 'react';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner/LoadingSpinner';
import { api } from '../../../shared/api/axios';
import { getApiErrorMessage } from '../../../shared/api/types';
import headerUserIcon from '../../../assets/brand/header-user-outlined.svg';
import { ChatAvatar } from '../../../shared/components/ChatAvatar/ChatAvatar';
import type { ChatMessageResponse, CounselTicketSummaryResponse } from '../types';

function MessageBubble({ message }: { message: ChatMessageResponse }) {
  if (message.sender === 'USER') {
    return (
      <div className="flex flex-col items-end gap-1">
        <div className="max-w-[560px] whitespace-pre-wrap rounded-2xl rounded-tr-sm bg-primary px-5 pt-2.5 pb-3 text-sm font-medium text-white">
          {message.content}
        </div>
        {message.attachmentUrl && (
          <img
            src={message.attachmentUrl}
            alt="첨부 이미지"
            className="max-w-[240px] rounded-xl border border-border object-cover"
          />
        )}
      </div>
    );
  }

  // COUNSELOR/BOT — 좌측 정렬, 상담 챗봇 화면(ChatBotPage)과 동일한 아바타+말풍선 배치로 통일.
  return (
    <div className="flex items-start gap-2.5">
      {message.sender === 'COUNSELOR' && (
        <ChatAvatar icon={headerUserIcon} bgClassName="bg-surface-sunken" className="border border-border" />
      )}
      <div className="flex flex-col items-start gap-1">
        {message.sender === 'COUNSELOR' && message.counselorName && (
          <span className="text-xs font-medium text-text-muted">상담원 {message.counselorName}</span>
        )}
        <div className="max-w-[560px] whitespace-pre-wrap rounded-2xl rounded-tl-sm border border-border bg-white px-5 py-3 text-sm font-medium text-primary">
          {message.content}
        </div>
      </div>
    </div>
  );
}

type Props = {
  ticket: CounselTicketSummaryResponse | null;
  messages: ChatMessageResponse[];
  loading: boolean;
  error: string | null;
  onStartNewCounsel: () => void;
};

export function ConversationPanel({ ticket, messages, loading, error, onStartNewCounsel }: Props) {
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState<string | null>(null);

  async function handleExport() {
    if (!ticket) return;
    setExporting(true);
    setExportError(null);
    try {
      const res = await api.get<Blob>(`/counsel/tickets/${ticket.id}/export`, { responseType: 'blob' });
      const disposition = res.headers['content-disposition'] as string | undefined;
      const match = disposition?.match(/filename="([^"]+)"/);
      const filename = match?.[1] ?? `${ticket.ticketNumber}.txt`;
      const url = URL.createObjectURL(res.data);
      const link = document.createElement('a');
      link.href = url;
      link.download = filename;
      link.click();
      URL.revokeObjectURL(url);
    } catch (err) {
      setExportError(getApiErrorMessage(err, '대화 내보내기에 실패했습니다.'));
    } finally {
      setExporting(false);
    }
  }

  if (!ticket) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-3 text-text-muted">
        <p className="m-0 text-sm">상담 이력이 없습니다.</p>
        <button
          type="button"
          onClick={onStartNewCounsel}
          className="rounded-full bg-primary px-5 py-2.5 text-sm font-semibold text-white"
        >
          새 상담 시작
        </button>
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
        <button
          type="button"
          onClick={() => void handleExport()}
          disabled={exporting}
          className="inline-flex items-center gap-1.5 rounded-full border border-border px-3 py-1.5 text-xs font-medium text-primary transition-colors hover:bg-surface-sunken disabled:opacity-50"
        >
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
            <path d="M7 10l5 5 5-5" />
            <path d="M12 15V3" />
          </svg>
          대화 내보내기
        </button>
      </div>

      {exportError && <p className="mx-6 mt-2 text-xs text-red-600">{exportError}</p>}

      <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto px-6 py-6">
        {loading && <LoadingSpinner className="flex items-center justify-center py-6" />}
        {error && <p className="text-sm text-red-600">{error}</p>}
        {!loading && !error && messages.length === 0 && (
          <p className="text-sm text-text-muted">대화 내용이 없습니다.</p>
        )}
        {!loading &&
          !error &&
          messages.map((message) => <MessageBubble key={message.id} message={message} />)}
      </div>
    </div>
  );
}
