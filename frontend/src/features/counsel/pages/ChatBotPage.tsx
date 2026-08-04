import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import chetRobotIcon from '../../../assets/brand/chet-robot.svg';
import { ChatAvatar } from '../../../shared/components/ChatAvatar/ChatAvatar';
import { ChatInputBox } from '../../../shared/components/ChatInputBox/ChatInputBox';
import { TypingIndicatorBubble } from '../../../shared/components/TypingIndicatorBubble/TypingIndicatorBubble';
import { MessageBubble } from '../components/ConversationPanel';
import type { ChatBotLogEntry } from '../hooks/useChatBot';
import { useChatBot } from '../hooks/useChatBot';

function BotAvatar() {
  return <ChatAvatar icon={chetRobotIcon} bgClassName="bg-primary" />;
}

// 고객지원 > 상담 챗봇(#20, HAJA-33) — 시나리오 트리를 버튼으로 타고 내려가다가 상담원 연결
// 리프에서 티켓을 생성한다. 실시간 상담원 채팅(WebSocket)은 후속 범위 — 여기서는 연결 완료
// 안내와 "내 상담 이력" 이동만 제공한다(입력창은 시각적 자리만, 실제 전송은 아직 미구현).
// ?category=INSPECTION_REPORT 같은 쿼리로 들어오면(퀵상담 FAB 딥링크) 최상위 4개 버튼을 생략하고
// 그 카테고리 하위 옵션으로 바로 진입한다.
export function ChatBotPage() {
  const [searchParams] = useSearchParams();
  const category = searchParams.get('category') ?? undefined;
  const {
    log,
    loading,
    error,
    connecting,
    selectButton,
    retry,
    activeTicket,
    messages,
    sendMessage,
    sendTyping,
    counselorTyping,
    endCounsel,
    ending,
    endError,
  } = useChatBot(category);
  // AiAssistantPage와 동일 패턴 — 새 항목이 추가될 때마다 하단 sentinel로 스크롤해
  // 방금 나온 봇 말풍선/버튼 + 입력창까지 함께 보이게 한다.
  const bottomRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    bottomRef.current?.scrollIntoView?.({ behavior: 'smooth', block: 'end' });
  }, [log, loading, connecting, error, messages, counselorTyping]);

  const [draft, setDraft] = useState('');
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

  return (
    <div className="flex h-full flex-col bg-surface-muted p-5">
      <div className="flex min-h-0 flex-1 flex-col overflow-hidden rounded-[20px] border border-border bg-white shadow-[0px_1px_2px_0px_rgba(0,0,0,0.05)]">
        <div className="flex items-center gap-3 border-b border-border px-6 py-4">
          <BotAvatar />
          <div>
            <h1 className="m-0 text-base font-semibold text-primary">자동 응답 봇</h1>
            <p className="m-0 flex items-center gap-1.5 text-xs text-text-muted">
              <span className="size-1.5 rounded-full bg-emerald-500" aria-hidden="true" />
              세션 진행중
            </p>
          </div>
        </div>

        <div className="flex min-h-0 flex-1 flex-col">
          <div role="log" className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto px-6 py-6">
            {log.map((entry) => (
              <ChatBotLogItem key={entry.id} entry={entry} onSelect={(b) => void selectButton(b)} />
            ))}

            {loading && (
              <div className="flex items-start gap-2.5">
                <BotAvatar />
                <div className="flex items-center gap-1.5 rounded-2xl rounded-tl-sm border border-border bg-white px-5 py-4">
                  <span className="sr-only">불러오는 중...</span>
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

            {connecting && (
              <div className="flex items-center gap-2 rounded-full bg-surface-sunken px-4 py-2 text-xs font-medium text-text-muted">
                <span className="flex gap-1" aria-hidden="true">
                  {[0, 1, 2].map((i) => (
                    <span
                      key={i}
                      className="size-1.5 animate-bounce rounded-full bg-text-muted"
                      style={{ animationDelay: `${i * 0.15}s` }}
                    />
                  ))}
                </span>
                상담원 연결 중입니다...
              </div>
            )}

            {/* 상담원 연결 후 실시간 대화(#1000 후속) — 이전엔 "내 상담 이력에서 확인하기" 링크로
                다른 페이지로 보냈으나, 이미 연결된 상담을 그 자리에서 이어갈 수 있어야 한다는
                피드백에 따라 챗봇 로그 바로 아래에서 실제 메시지를 주고받는다. */}
            {activeTicket && messages.map((message) => <MessageBubble key={message.id} message={message} />)}
            {counselorTyping && <TypingIndicatorBubble />}
          </div>

          {error && (
            <div className="flex flex-col items-start gap-2 px-6 pb-2">
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
          {endError && <p className="mx-6 mb-2 text-sm text-red-600">{endError}</p>}
        </div>

        {activeTicket?.status === 'IN_PROGRESS' && (
          <div className="flex justify-end px-6 pt-1">
            <button
              type="button"
              onClick={() => void endCounsel()}
              disabled={ending}
              className="rounded-full border border-border px-4 py-1.5 text-xs font-semibold text-primary transition-colors hover:bg-surface-sunken disabled:opacity-50"
            >
              {ending ? '종료 중...' : '상담 종료'}
            </button>
          </div>
        )}

        {/* 상담원 연결 전(activeTicket 없음)에는 AiAssistantPage와 동일한 입력 바 스타일을 비활성으로만
            노출(디자인 통일). 연결 후(IN_PROGRESS)엔 공통 ChatInputBox로 실제 전송이 가능해진다. */}
        <div className="flex justify-center px-6 pb-6 pt-2">
          {activeTicket ? (
            <ChatInputBox
              value={draft}
              onChange={handleDraftChange}
              onSubmit={handleSend}
              disabled={activeTicket.status !== 'IN_PROGRESS'}
              placeholder={
                activeTicket.status === 'IN_PROGRESS'
                  ? '메시지를 입력하세요'
                  : '메시지를 입력하세요 (상담원 연결 시 활성화됩니다)'
              }
            />
          ) : (
            <div className="flex w-full max-w-[600px] items-center gap-2 rounded-full border border-border bg-white/70 p-2 opacity-60 shadow-[0px_4px_6px_-4px_rgba(0,0,0,0.1)] backdrop-blur-[10px]">
              <input
                type="text"
                disabled
                placeholder="메시지를 입력하세요 (상담원 연결 시 활성화됩니다)"
                aria-label="메시지 입력"
                className="min-w-0 flex-1 border-none bg-transparent px-4 py-2.5 text-base text-primary outline-none placeholder:text-text-muted"
              />
              <button
                type="button"
                disabled
                aria-label="전송"
                className="inline-flex size-10 shrink-0 items-center justify-center rounded-full bg-primary disabled:opacity-50"
              >
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                  <path d="M12 19V5M5 12l7-7 7 7" />
                </svg>
              </button>
            </div>
          )}
        </div>
        <div ref={bottomRef} />
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
        <div className="flex items-start gap-2.5">
          <BotAvatar />
          <div className="flex max-w-[560px] flex-col items-start gap-2">
            <div className="whitespace-pre-wrap rounded-2xl rounded-tl-sm border border-border bg-white px-5 py-4 text-sm font-medium text-primary">
              {entry.text}
            </div>
            {entry.actionRoute && entry.actionLabel && (
              <Link
                to={entry.actionRoute}
                className="rounded-full border border-point px-4 py-2 text-sm font-medium text-point transition-colors hover:bg-point/5"
              >
                {entry.actionLabel}
              </Link>
            )}
          </div>
        </div>
      );
    case 'user-choice':
      return (
        <div className="flex justify-end">
          <div className="rounded-2xl rounded-tr-sm bg-primary px-5 py-2.5 text-sm font-medium text-white">
            {entry.label}
          </div>
        </div>
      );
    case 'bot-buttons':
      return (
        <div className="ml-[42px] flex flex-wrap gap-2">
          {entry.buttons.map((button) => (
            <button
              key={button.id}
              type="button"
              onClick={() => onSelect(button)}
              className={
                button.leadsToCounselor
                  ? 'rounded-full border border-point bg-white px-4 py-2 text-sm font-medium text-point transition-colors hover:bg-point/5'
                  : 'rounded-full border border-border bg-white px-4 py-2 text-sm font-medium text-primary transition-colors hover:bg-surface-sunken'
              }
            >
              {button.buttonLabel}
            </button>
          ))}
        </div>
      );
    case 'ticket-created':
      // 이 자리에서 바로 실시간 대화가 이어진다(#1000 후속) — 배정 전까지는 대기 안내만,
      // 배정되면 useChatBot의 activeTicket/messages가 갱신되어 아래에 실제 대화가 표시된다.
      return (
        <div className="ml-[42px] flex flex-col items-start gap-3 rounded-2xl border border-primary/20 bg-surface-sunken px-5 py-4">
          <p className="m-0 text-sm font-medium text-primary">
            상담원 연결을 요청했습니다. 배정될 때까지 잠시만 기다려 주세요.
            {entry.ticket.queuePosition !== null && ` (대기 순번 ${entry.ticket.queuePosition}번)`}
          </p>
        </div>
      );
    default:
      return null;
  }
}
