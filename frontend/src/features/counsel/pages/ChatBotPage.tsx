import { Link } from 'react-router-dom';
import type { ChatBotLogEntry } from '../hooks/useChatBot';
import { useChatBot } from '../hooks/useChatBot';

// 고객지원 > 상담 챗봇(#20, HAJA-33) — 시나리오 트리를 버튼으로 타고 내려가다가 상담원 연결
// 리프에서 티켓을 생성한다. 실시간 상담원 채팅(WebSocket)은 후속 범위 — 여기서는 연결 완료
// 안내와 "내 상담 이력" 이동만 제공한다.
export function ChatBotPage() {
  const { log, loading, error, connecting, selectButton, retry } = useChatBot();

  return (
    <div className="flex h-full flex-col p-5">
      <div className="flex min-h-0 flex-1 flex-col overflow-hidden rounded-[20px] bg-white shadow-[0px_1px_2px_0px_rgba(0,0,0,0.05)]">
        <div className="flex flex-col border-b border-border px-6 py-4">
          <h1 className="m-0 text-xl font-medium text-primary">상담 챗봇</h1>
          <p className="m-0 text-sm font-medium text-text-default">
            궁금하신 내용을 카테고리로 선택하면 안내해드려요
          </p>
        </div>

        <div role="log" className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto px-6 py-6">
          {log.map((entry) => (
            <ChatBotLogItem key={entry.id} entry={entry} onSelect={(b) => void selectButton(b)} />
          ))}

          {(loading || connecting) && (
            <div className="flex justify-start">
              <div className="flex items-center gap-1.5 rounded-2xl rounded-tl-sm border border-border bg-white px-5 py-4">
                <span className="sr-only">{connecting ? '상담원 연결 중...' : '불러오는 중...'}</span>
                {[0, 1, 2].map((i) => (
                  <span
                    key={i}
                    className="size-2 animate-bounce rounded-full bg-text-muted"
                    style={{ animationDelay: `${i * 0.15}s` }}
                    aria-hidden="true"
                  />
                ))}
              </div>
            </div>
          )}

          {error && (
            <div className="flex flex-col items-start gap-2">
              <p className="m-0 text-sm text-red-600">{error}</p>
              <button
                type="button"
                onClick={() => void retry()}
                className="rounded-full border border-border px-4 py-1.5 text-sm font-medium text-primary hover:bg-surface-sunken"
              >
                다시 시도
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

function ChatBotLogItem({
  entry,
  onSelect,
}: {
  entry: ChatBotLogEntry;
  onSelect: (button: import('../types').BotScenarioButtonResponse) => void;
}) {
  switch (entry.kind) {
    case 'bot-text':
      return (
        <div className="flex justify-start">
          <div className="max-w-[600px] whitespace-pre-wrap rounded-2xl rounded-tl-sm border border-border bg-white px-5 py-4 text-sm font-medium text-primary">
            {entry.text}
          </div>
        </div>
      );
    case 'user-choice':
      return (
        <div className="flex justify-end">
          <div className="rounded-2xl rounded-tr-sm bg-surface-sunken px-5 py-2.5 text-sm font-medium text-primary">
            {entry.label}
          </div>
        </div>
      );
    case 'bot-buttons':
      return (
        <div className="flex flex-wrap gap-2">
          {entry.buttons.map((button) => (
            <button
              key={button.id}
              type="button"
              onClick={() => onSelect(button)}
              className="rounded-full border border-border bg-white px-4 py-2 text-sm font-medium text-primary transition-colors hover:bg-surface-sunken"
            >
              {button.buttonLabel}
            </button>
          ))}
        </div>
      );
    case 'ticket-created':
      return (
        <div className="flex flex-col items-start gap-3 rounded-2xl border border-primary/20 bg-surface-sunken px-5 py-4">
          <p className="m-0 text-sm font-medium text-primary">
            상담원과 연결됐습니다. 잠시만 기다려 주세요.
            {entry.ticket.queuePosition !== null && ` (대기 순번 ${entry.ticket.queuePosition}번)`}
          </p>
          <Link
            to="/support/history"
            className="rounded-full bg-primary px-4 py-2 text-sm font-semibold text-white"
          >
            내 상담 이력에서 확인하기
          </Link>
        </div>
      );
    default:
      return null;
  }
}
