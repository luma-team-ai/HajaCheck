import { type FormEvent, useEffect, useRef, useState } from 'react';
import { AIErrorFallback } from '../../../shared/components/AIErrorFallback/AIErrorFallback';
import { AILoadingIndicator } from '../../../shared/components/AILoadingIndicator/AILoadingIndicator';
import { useRagChat } from '../hooks/useRagChat';
import type { SourceCitation } from '../types';

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

// 고객지원 > AI 어시스턴트(RAG 법규 Q&A) — dev-08-01 / HAJA-32 / FR-6.
// 앱 셸(AppLayout: SideNavBar+Header+FAB)은 AppShellRoute가 감싸므로 여기서는 카드 본문만 렌더한다.
export function AiAssistantPage() {
  const { messages, loading, error, send, retry } = useRagChat();
  const [input, setInput] = useState('');
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' });
  }, [messages, loading, error]);

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    send(input);
    setInput('');
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

        {/* 메시지 영역 */}
        <div
          ref={scrollRef}
          className="flex min-h-0 flex-1 flex-col gap-6 overflow-y-auto px-6 py-6"
        >
          {messages.length === 0 && !loading && (
            <p className="m-0 text-sm text-text-muted">
              점검 기준이나 법규를 물어보세요. 답변에는 근거 출처가 함께 표시됩니다.
            </p>
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
                  <div className="flex flex-wrap items-center gap-2">
                    {message.sources.map((source) => (
                      <SourceChip key={source.chunk_ref} source={source} />
                    ))}
                  </div>
                )}
              </div>
            ),
          )}

          {loading && <AILoadingIndicator message="답변을 생성하고 있습니다..." />}
          {error && <AIErrorFallback onRetry={retry} />}
        </div>

        {/* 입력 — Figma: 반투명 알약 + 다크 원형 전송 버튼 */}
        <div className="flex justify-center px-6 pb-6">
          <form
            onSubmit={handleSubmit}
            className="flex w-full max-w-[600px] items-center gap-2 rounded-full border border-border bg-white/70 p-2 shadow-[0px_4px_6px_-4px_rgba(0,0,0,0.1)] backdrop-blur-[10px] transition-colors focus-within:border-point focus-within:ring-2 focus-within:ring-point/30"
          >
            <input
              type="text"
              value={input}
              onChange={(event) => setInput(event.target.value)}
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
      </div>
    </div>
  );
}
