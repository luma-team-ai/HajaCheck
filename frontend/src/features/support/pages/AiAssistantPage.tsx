import { type FormEvent, useEffect, useRef, useState } from 'react';
import { AIErrorFallback } from '../../../shared/components/AIErrorFallback/AIErrorFallback';
import { useRagChat } from '../hooks/useRagChat';
import type { SourceCitation } from '../types';

// 로딩 상태 — 메시지 로그 안에 다음 어시스턴트 말풍선과 같은 위치·모양으로 넣어, 답변이
// 도착하면 같은 자리에서 그대로 전환되게 한다(사용자 요청). 로그(role=log) 밖 별도 영역에
// 두면 실제 답변이 렌더되는 위치와 달라져 "말풍선이 엉뚱한 하단에 나온다"는 문제가 생겼다.
// 이제 로그 안의 평범한 항목이라 별도 role=status는 불필요(로그 자체 aria-live=polite가 안내).
function AssistantTypingBubble() {
  return (
    <div className="flex justify-start">
      <div className="flex items-center gap-1.5 rounded-2xl rounded-tl-sm border border-border bg-white px-5 py-4">
        <span className="sr-only">답변을 생성하고 있습니다...</span>
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
  );
}

// 출처 칩 — 설계 §5: title + locator를 그대로 표시(FE 재조립·Chroma 재조회 금지).
function SourceChip({ source }: { source: SourceCitation }) {
  const label = source.locator ? `${source.title} ${source.locator}` : source.title;
  return (
    <span className="inline-flex items-center gap-2 rounded-[10px] border border-border bg-white px-3 py-2 text-sm font-medium text-primary">
      <svg
        width="12"
        height="14"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
      >
        <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
        <path d="M14 2v6h6" />
      </svg>
      {label}
    </span>
  );
}

// 참고문서 토글 — 답변이 길어질수록 출처 칩이 항상 펼쳐져 있으면 지저분해 보여서, 기본은
// 접어두고 필요할 때만 펼치는 방식으로 바꾼다(사용자 요청). 메시지별 독립 상태라 로컬 useState로 충분.
function SourcesToggle({ sources }: { sources: SourceCitation[] }) {
  const [open, setOpen] = useState(false);

  return (
    <div className="flex flex-col items-start gap-2">
      <button
        type="button"
        onClick={() => setOpen((prev) => !prev)}
        aria-expanded={open}
        className="inline-flex items-center gap-1.5 rounded-full border border-border bg-white px-3 py-1.5 text-xs font-medium text-text-muted transition-colors hover:bg-surface-sunken"
      >
        <svg
          width="10"
          height="10"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2.5"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
          className={`transition-transform ${open ? 'rotate-180' : ''}`}
        >
          <path d="M6 9l6 6 6-6" />
        </svg>
        참고문서 {sources.length}건
      </button>
      {open && (
        <div className="flex flex-wrap items-center gap-2">
          {sources.map((source) => (
            <SourceChip key={`${source.collection}-${source.chunk_ref}`} source={source} />
          ))}
        </div>
      )}
    </div>
  );
}

// 빈 상태 예시 질문 — 사용자가 뭘 물어야 할지 몰라 "안녕" 같은 무관한 질의를 보내는 것을
// 사전에 줄이기 위한 클릭 가능한 칩(오분류 방지가 문구 개선보다 근본적).
const EXAMPLE_QUESTIONS = [
  '안전점검의 종류에는 어떤 것들이 있나요?',
  '정밀안전진단은 언제 실시하나요?',
  '안전등급은 어떻게 나뉘나요?',
];

// 고객지원 > AI 어시스턴트(RAG 법규 Q&A) — dev-08-01 / HAJA-32 / FR-6.
// 앱 셸(AppLayout: SideNavBar+Header+FAB)은 AppShellRoute가 감싸므로 여기서는 카드 본문만 렌더한다.
export function AiAssistantPage() {
  const { messages, loading, error, send, retry } = useRagChat();
  const [input, setInput] = useState('');
  // 로그 맨 끝의 빈 sentinel로 스크롤한다 — 컨테이너의 scrollHeight를 직접 계산하면 새 콘텐츠가
  // 마운트되는 타이밍과 어긋나 끝까지 안 내려가는 경우가 있었다. scrollIntoView는 그 시점의
  // 실제 레이아웃 기준으로 동작해 더 안정적이다.
  const bottomRef = useRef<HTMLDivElement>(null);
  // IME(한글) 조합 중 Enter는 조합 확정용 — 전송과 구분하기 위한 플래그
  const composingRef = useRef(false);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [messages, loading, error]);

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    // 조합 중 Enter(한글 확정)는 전송하지 않는다 — 조기/중복 전송 방지
    if (composingRef.current) return;
    // 실제로 전송된 경우에만 입력창을 비운다 — 전송 중(no-op)에 타이핑한 질문 유실 방지
    if (send(input)) setInput('');
  }

  return (
    <div className="flex h-full flex-col p-5">
      <div className="flex min-h-0 flex-1 flex-col overflow-hidden rounded-[20px] bg-white shadow-[0px_1px_2px_0px_rgba(0,0,0,0.05)]">
        {/* 카드 헤더 */}
        <div className="flex flex-col border-b border-border px-6 py-4">
          <h1 className="m-0 text-xl font-medium text-primary">AI 어시스턴트</h1>
          <p className="m-0 text-sm font-medium text-text-default">
            점검 기준·법규 Q&amp;A · 답변에 출처가 표시됩니다
          </p>
        </div>

        {/* 대화 영역: 로그(스크롤) + 에러 상태를 분리해 live region 중첩을 피한다 */}
        <div className="flex min-h-0 flex-1 flex-col">
          {/* 메시지 로그 — role=log(암묵 aria-live=polite)로 새 답변을 스크린리더가 안내.
              로딩 중인 다음 어시스턴트 말풍선도 이 안에 넣어 실제 답변과 같은 위치에서 전환되게 한다. */}
          <div role="log" className="flex min-h-0 flex-1 flex-col gap-6 overflow-y-auto px-6 pt-6 pb-2">
            {messages.length === 0 && !loading && (
              <div className="flex flex-col gap-3">
                <p className="m-0 text-sm text-text-muted">
                  점검 기준이나 법규를 물어보세요. 답변에는 근거 출처가 함께 표시됩니다.
                </p>
                <div className="flex flex-wrap gap-2">
                  {EXAMPLE_QUESTIONS.map((question) => (
                    <button
                      key={question}
                      type="button"
                      onClick={() => send(question)}
                      className="rounded-full border border-border bg-white px-4 py-2 text-sm font-medium text-primary transition-colors hover:bg-surface-sunken"
                    >
                      {question}
                    </button>
                  ))}
                </div>
              </div>
            )}

            {messages.map((message) =>
              message.role === 'user' ? (
                <div key={message.id} className="flex justify-end">
                  <div className="max-w-[768px] whitespace-pre-wrap rounded-2xl rounded-tr-sm bg-surface-sunken px-5 pt-2.5 pb-3 text-base font-medium text-primary">
                    {message.text}
                  </div>
                </div>
              ) : (
                <div key={message.id} className="flex flex-col items-start gap-3">
                  <div className="max-w-[816px] whitespace-pre-wrap rounded-2xl rounded-tl-sm border border-border bg-white px-5 py-4 text-base font-medium text-primary">
                    {message.text}
                  </div>
                  {message.sources && message.sources.length > 0 && (
                    <SourcesToggle sources={message.sources} />
                  )}
                </div>
              ),
            )}

            {loading && <AssistantTypingBubble />}
          </div>

          {/* 에러 상태 — 로그(live region) 밖에 두어 중첩 announce 방지. AIErrorFallback은 자체 role=status */}
          {error && (
            <div className="px-6 pb-2">
              <AIErrorFallback onRetry={retry} />
            </div>
          )}
        </div>

        {/* 입력 — Figma: 반투명 알약 + 다크 원형 전송 버튼 */}
        <div className="flex justify-center px-6 pb-6 pt-2">
          <form
            onSubmit={handleSubmit}
            className="flex w-full max-w-[600px] items-center gap-2 rounded-full border border-border bg-white/70 p-2 shadow-[0px_4px_6px_-4px_rgba(0,0,0,0.1)] backdrop-blur-[10px] transition-colors focus-within:border-point focus-within:ring-2 focus-within:ring-point/30"
          >
            <input
              type="text"
              value={input}
              onChange={(event) => setInput(event.target.value)}
              onCompositionStart={() => {
                composingRef.current = true;
              }}
              onCompositionEnd={() => {
                composingRef.current = false;
              }}
              placeholder="점검 기준이나 법규를 물어보세요"
              aria-label="질문 입력"
              className="min-w-0 flex-1 border-none bg-transparent px-4 py-2.5 text-base text-primary caret-point outline-none placeholder:text-text-muted focus:outline-none"
            />
            <button
              type="submit"
              disabled={loading || input.trim() === ''}
              aria-label="전송"
              className="inline-flex size-10 shrink-0 items-center justify-center rounded-full bg-primary disabled:opacity-50"
            >
              <svg
                width="16"
                height="16"
                viewBox="0 0 24 24"
                fill="none"
                stroke="#fff"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
                aria-hidden="true"
              >
                <path d="M12 19V5M5 12l7-7 7 7" />
              </svg>
            </button>
          </form>
        </div>
        {/* 스크롤 기준점 — 로그 안이 아니라 입력창 바로 아래(카드 맨 끝)에 둬서, 새 답변이
            와도 입력창까지 함께 화면에 보이게 한다(로그 자체 스크롤만으로는 입력창이 화면
            밖으로 밀려나는 경우가 있었다). */}
        <div ref={bottomRef} />
      </div>
    </div>
  );
}
