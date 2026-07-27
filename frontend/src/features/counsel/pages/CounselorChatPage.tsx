import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner/LoadingSpinner';
import { getApiErrorMessage } from '../../../shared/api/types';
import { counselApi } from '../api/counselApi';
import { useCounselSocket } from '../hooks/useCounselSocket';
import type { ChatMessageResponse, CounselTicketDetailResponse } from '../types';

// 상담원 콘솔 > 채팅(#1001, HAJA-495) — 대기열에서 클레임한 티켓의 실시간 대화 + 종료 버튼.
// 앱 셸(AppLayout)은 CounselorShellRoute가 감싸므로 여기서는 카드 본문만 렌더한다.
// 피그마 디자인이 아직 없어 기존 ConversationPanel(고객 관점 말풍선 좌우 배치)을 그대로 재사용하지
// 않고, 상담원 관점(자신의 발화=우측)으로 최소 마크업을 새로 짰다 — 실 디자인 확정 시 재검토 필요.
export function CounselorChatPage() {
  const { id } = useParams<{ id: string }>();
  const ticketId = id ? Number(id) : null;
  const navigate = useNavigate();
  const location = useLocation();

  // 대기열 페이지가 클레임 직후 navigate(..., { state: { ticket } })로 넘겨주는 값 — 새로고침 등으로
  // 없을 수 있어(직접 URL 진입) optional로 다룬다. 백엔드에 티켓 단건 조회 엔드포인트가 없어(대화
  // 목록 API만 존재) 그 경우엔 최소 정보(티켓 번호)만 헤더에 보여준다.
  const initialTicket = (location.state as { ticket?: CounselTicketDetailResponse } | null)?.ticket ?? null;
  // 대화 헤더에 쓸 티켓 정보 — 백엔드에 단건 조회 엔드포인트가 없어(대화 목록 API만 존재) 클레임 직후
  // navigate state로 받은 값을 그대로 표시한다. 이 화면 안에서 갱신할 일이 없어(제목·티켓번호는
  // 불변) setter는 두지 않는다 — 새로고침 등으로 값이 없으면 최소 정보(#헤더 fallback)만 보여준다.
  const [ticket] = useState<CounselTicketDetailResponse | null>(initialTicket);
  const [messages, setMessages] = useState<ChatMessageResponse[]>([]);
  const [messagesLoading, setMessagesLoading] = useState(true);
  const [messagesError, setMessagesError] = useState<string | null>(null);
  const [draft, setDraft] = useState('');
  const [resolving, setResolving] = useState(false);
  const [resolveError, setResolveError] = useState<string | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (ticketId === null) return;
    let cancelled = false;
    setMessagesLoading(true);
    setMessagesError(null);
    counselApi
      .getMessages(ticketId)
      .then((res) => {
        if (!cancelled) setMessages(res.data);
      })
      .catch((err: unknown) => {
        if (!cancelled) setMessagesError(getApiErrorMessage(err, '대화를 불러오지 못했습니다.'));
      })
      .finally(() => {
        if (!cancelled) setMessagesLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [ticketId]);

  const { connected, sendMessage } = useCounselSocket(ticketId, {
    onMessage: (message) => setMessages((prev) => [...prev, message]),
    // 다른 경로(예: 이용자 오프라인 이탈, PLATFORM_ADMIN 강제 종료)로 티켓이 끝나도 화면이 이를
    // 반영하도록 onEnded도 구독한다 — 직접 종료 버튼을 누른 경우는 handleResolve가 이미 처리.
    onEnded: () => navigate('/counsel-console/queue'),
  });

  useEffect(() => {
    // jsdom(vitest)에는 scrollIntoView가 구현돼 있지 않아(Element.prototype에 없음) 테스트 환경에서
    // 호출 시 TypeError로 렌더 자체가 실패한다 — 실제 브라우저에서만 존재하는 메서드이므로 방어적으로 호출.
    scrollRef.current?.scrollIntoView?.({ block: 'end' });
  }, [messages]);

  function handleSend() {
    const content = draft.trim();
    if (!content) return;
    sendMessage(content);
    setDraft('');
  }

  async function handleResolve() {
    if (ticketId === null) return;
    setResolving(true);
    setResolveError(null);
    try {
      await counselApi.resolve(ticketId);
      navigate('/counsel-console/queue');
    } catch (err) {
      setResolveError(getApiErrorMessage(err, '상담 종료에 실패했습니다.'));
    } finally {
      setResolving(false);
    }
  }

  return (
    <div className="flex h-full flex-col bg-surface-muted p-5">
      <div className="flex min-h-0 flex-1 flex-col overflow-hidden rounded-[20px] border border-border bg-white shadow-[0px_1px_2px_0px_rgba(0,0,0,0.05)]">
        <div className="flex items-center justify-between border-b border-border px-6 py-4">
          <div className="flex items-center gap-2">
            <h1 className="m-0 text-lg font-semibold text-primary">
              {ticket?.title ?? `상담 티켓 #${ticketId ?? ''}`}
            </h1>
            {ticket?.ticketNumber && (
              <span className="text-xs text-text-muted">#{ticket.ticketNumber}</span>
            )}
            <span
              className={`text-xs font-medium ${connected ? 'text-indigo-600' : 'text-text-muted'}`}
            >
              {connected ? '연결됨' : '연결 중...'}
            </span>
          </div>
          <button
            type="button"
            onClick={() => void handleResolve()}
            disabled={resolving}
            className="rounded-full border border-border px-4 py-2 text-sm font-semibold text-primary transition-colors hover:bg-surface-sunken disabled:opacity-50"
          >
            {resolving ? '종료 중...' : '상담 종료'}
          </button>
        </div>

        {resolveError && <p className="mx-6 mt-2 text-xs text-red-600">{resolveError}</p>}

        <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto px-6 py-6">
          {messagesLoading && <LoadingSpinner className="flex items-center justify-center py-6" />}
          {messagesError && <p className="text-sm text-red-600">{messagesError}</p>}
          {!messagesLoading && !messagesError && messages.length === 0 && (
            <p className="text-sm text-text-muted">대화 내용이 없습니다.</p>
          )}
          {!messagesLoading &&
            !messagesError &&
            messages.map((message) => (
              <div
                key={message.id}
                className={`flex flex-col gap-1 ${message.sender === 'COUNSELOR' ? 'items-end' : 'items-start'}`}
              >
                {message.sender !== 'COUNSELOR' && (
                  <span className="text-xs font-medium text-text-muted">
                    {message.sender === 'USER' ? '고객' : '챗봇'}
                  </span>
                )}
                <div
                  className={`max-w-[560px] whitespace-pre-wrap rounded-2xl px-5 py-3 text-sm font-medium ${
                    message.sender === 'COUNSELOR'
                      ? 'rounded-tr-sm bg-primary text-white'
                      : 'rounded-tl-sm border border-border bg-white text-primary'
                  }`}
                >
                  {message.content}
                </div>
              </div>
            ))}
          <div ref={scrollRef} />
        </div>

        <div className="flex items-center gap-3 border-t border-border px-6 py-4">
          <input
            type="text"
            aria-label="메시지 입력"
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') handleSend();
            }}
            placeholder="메시지를 입력하세요"
            className="min-w-0 flex-1 rounded-full border border-border px-4 py-2.5 text-sm outline-none focus:border-primary"
          />
          <button
            type="button"
            onClick={handleSend}
            disabled={!connected || !draft.trim()}
            className="shrink-0 rounded-full bg-primary px-5 py-2.5 text-sm font-semibold text-white disabled:opacity-50"
          >
            전송
          </button>
        </div>
      </div>
    </div>
  );
}
