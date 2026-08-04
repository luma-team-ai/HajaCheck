import { useCallback, useEffect, useRef, useState } from 'react';
import type { ApiError } from '../../../shared/api/types';
import { supportApi } from '../api/supportApi';
import { clearRagSessionId, getRagSessionId, setRagSessionId } from '../utils/ragSessionId';
import type { ChatMessage, ChatSessionMessageResponse, SourceCitation } from '../types';

let idSeq = 0;
function nextId() {
  idSeq += 1;
  return `msg-${Date.now()}-${idSeq}`;
}

// 검색 0건 안내 문구 — 목(support.mock)과 이 훅의 RAG_NO_RESULT 분기가 공유(단일 출처).
// 시스템 에러처럼 읽히지 않도록 안내형 문구 + 예시 질문을 함께 제시한다.
export const RAG_NO_RESULT_TEXT =
  '이 질문엔 답변드리기 어려워요. 점검 기준·법규 관련 질문을 해주시면 도움드릴게요.\n(예: "정기안전점검은 얼마나 자주 하나요?")';

function isApiError(err: unknown): err is ApiError {
  return typeof err === 'object' && err !== null && 'code' in err && 'message' in err;
}

// 세션 이력 조회(ChatSessionMessageResponse[])를 화면 표시용 ChatMessage[]로 매핑한다(HAJA-668).
// 필드명 차이: 백엔드 sender(USER/BOT/COUNSELOR)→role(user/assistant), citations는 SourceCitation과
// 형태가 다르다(documentId→doc_id, chunkRef→chunk_ref, title/collection 필드가 세션 이력 응답엔
// 없음 — 원본 질의(ragChat) 응답에만 있는 필드라 이력 복원 시엔 snippet을 title 대용으로 쓰고
// collection은 화면 표시(SourceChip: title+locator만 사용)에 영향 없어 'regulations' 기본값을 둔다).
function toChatMessage(msg: ChatSessionMessageResponse): ChatMessage {
  const sources: SourceCitation[] = msg.citations.map((c) => ({
    // documentId는 백엔드 Long(JSON number)이라 SourceCitation.doc_id 계약(문자열)에 맞춰 변환한다
    // (PR #1563 P2 픽스 — 이전엔 number를 그대로 문자열 필드에 넣어 런타임 타입이 계약과 어긋났다).
    doc_id: String(c.documentId),
    title: c.snippet || String(c.documentId),
    collection: 'regulations',
    locator: c.locator,
    chunk_ref: c.chunkRef,
  }));
  return {
    id: `session-msg-${msg.id}`,
    role: msg.sender === 'USER' ? 'user' : 'assistant',
    text: msg.content,
    sources: msg.sender === 'USER' ? undefined : sources,
  };
}

// 고객지원 AI 어시스턴트(RAG 법규 Q&A) 채팅 상태 훅 — dev-08-01 / HAJA-32 / FR-6.
// HAJA-668(#1548): session_id를 localStorage에 저장/복원해 탭 재진입에도 대화 맥락을 유지한다(설계 §2).
// 메시지 누적 + 로딩/에러 관리. 실 호출은 supportApi.ragChat(설계 §7 /api/ai/rag-chat) 경유.
export function useRagChat() {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [lastQuery, setLastQuery] = useState<string | null>(null);
  // 인플라이트 가드 — loading은 비동기 state라 빠른 연속 호출에서 중복 요청을 못 막는다.
  // ref로 동기 차단해 send/retry 이중 발화를 막는다.
  const inFlightRef = useRef(false);
  // 세션 ID는 렌더 트리거가 필요 없어(화면에 직접 노출 안 됨) ref로 관리 — localStorage가 SoT.
  const sessionIdRef = useRef<number | null>(null);
  // 마운트 복원(GET)이 도착하기 전에 사용자가 이미 send()했는지 표시(PR #1563 P2 픽스) — 복원
  // 응답이 뒤늦게 와서 setMessages로 통째로 교체하면 그 사이 주고받은 메시지가 화면에서 사라진다.
  // 한 번 true가 되면 이 훅 생명주기 동안 계속 true(복원 GET은 마운트당 1회뿐이라 재확인 불필요).
  const hasSentRef = useRef(false);
  // 세대 토큰 — startNewChat이 in-flight runQuery를 무효화하는 데 쓴다(아래 startNewChat 참고).
  const genRef = useRef(0);

  // 마운트 시 1회: localStorage에 세션이 있으면 이력을 복원한다(설계 §2 "유지·복원").
  useEffect(() => {
    const savedId = getRagSessionId();
    if (savedId === null) return;
    sessionIdRef.current = savedId;

    let cancelled = false;
    (async () => {
      try {
        const res = await supportApi.getSessionMessages(savedId);
        // cancelled(언마운트) 또는 그 사이 사용자가 이미 새 메시지를 보냈으면(PR #1563 P2 픽스)
        // 복원으로 화면을 덮어쓰지 않는다 — 방금 보낸 대화는 서버에도 이미 저장돼 있으므로
        // 다음 새로고침에서 자연히 포함된다.
        if (cancelled || hasSentRef.current) return;
        setMessages(res.data.map(toChatMessage));
      } catch {
        // 세션 만료/삭제 등(403 등) — 에러 화면 대신 조용히 새 대화로 취급(설계 §2 지시)
        if (cancelled) return;
        sessionIdRef.current = null;
        clearRagSessionId();
      }
    })();

    return () => {
      cancelled = true;
    };
  }, []);

  const runQuery = useCallback(async (query: string) => {
    // 이 호출이 속한 세대를 캡처(PR #1563 P2 픽스) — startNewChat이 genRef를 올리면 이 값과
    // 어긋나, 뒤늦게 도착한 이 호출의 응답이 "새 대화"에 섞여 들어가는 것을 막는다.
    const gen = genRef.current;
    inFlightRef.current = true;
    setError(null);
    setLoading(true);
    try {
      // 최초 질의(세션 없음)면 먼저 세션을 생성해 session_id를 확보한다(설계 §2 "생성 시점").
      let sessionId = sessionIdRef.current;
      if (sessionId === null) {
        const sessionRes = await supportApi.createSession();
        if (gen !== genRef.current) return; // 그 사이 startNewChat — 이 결과는 폐기
        sessionId = sessionRes.data.sessionId;
        sessionIdRef.current = sessionId;
        setRagSessionId(sessionId);
      }

      const res = await supportApi.ragChat({ query, sessionId: sessionId ?? undefined });
      if (gen !== genRef.current) return; // 그 사이 startNewChat — 이 결과는 폐기
      setMessages((prev) => [
        ...prev,
        { id: nextId(), role: 'assistant', text: res.data.answer, sources: res.data.sources },
      ]);
    } catch (err) {
      if (gen !== genRef.current) return; // 그 사이 startNewChat — 이 결과는 폐기
      // 확정 계약(설계 §9/§4.3): 백엔드가 0건을 success:false(RAG_NO_RESULT)로 준다 —
      // 이를 에러가 아니라 "근거 없음" 안내로 표시한다.
      if (isApiError(err) && err.code === 'RAG_NO_RESULT') {
        setMessages((prev) => [
          ...prev,
          { id: nextId(), role: 'assistant', text: RAG_NO_RESULT_TEXT, sources: [] },
        ]);
      } else if (isApiError(err)) {
        setError(err);
      } else {
        // 인터셉터를 거치지 않은 예외(파싱 실패 등) — 형이 어긋날 수 있어 안전한 기본 에러로 대체
        setError({
          code: 'UNKNOWN',
          message: 'AI 응답을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.',
        });
      }
    } finally {
      // 세대가 어긋나면(startNewChat이 이미 발생) loading/inFlightRef는 새 세대가 자체적으로
      // 관리 중이므로 여기서 건드리지 않는다 — 안 그러면 새 세대의 진행 중 상태를 지워버린다.
      if (gen === genRef.current) {
        setLoading(false);
        inFlightRef.current = false;
      }
    }
  }, []);

  // 실제로 전송을 시작했으면 true 반환 — 호출부가 입력창 clear 여부를 판단(전송 중 타이핑 유실 방지).
  const send = useCallback(
    (query: string): boolean => {
      const trimmed = query.trim();
      if (!trimmed || inFlightRef.current) return false;
      hasSentRef.current = true;
      setMessages((prev) => [...prev, { id: nextId(), role: 'user', text: trimmed }]);
      setLastQuery(trimmed);
      void runQuery(trimmed);
      return true;
    },
    [runQuery],
  );

  // 에러 후 재시도 — 마지막 질의를 사용자 말풍선 중복 없이 다시 호출
  const retry = useCallback(() => {
    if (lastQuery && !inFlightRef.current) void runQuery(lastQuery);
  }, [lastQuery, runQuery]);

  // "새 대화 시작" — 설계 §2 "초기화": 서버 세션 종료 API는 이번 범위 밖(P2 후속)이라 로컬
  // 초기화만 한다. 다음 질의에서 새 session_id가 발급되므로 기능상 맥락 초기화 효과는 동일하다.
  //
  // 진행 중(loading) 질의가 있어도 호출 가능하다(AiAssistantPage는 messages.length만 보고
  // loading은 안 봄) — 그래서 세대를 올려 그 in-flight runQuery의 응답이 도착해도
  // "새 대화"에 섞여 들어가지 않게 하고(PR #1563 P2 픽스), inFlightRef도 즉시 풀어 새 대화에서
  // 바로 다음 질의를 보낼 수 있게 한다(안 풀면 이전 요청이 끝날 때까지 send()가 조용히 막힘).
  const startNewChat = useCallback(() => {
    genRef.current += 1;
    inFlightRef.current = false;
    setLoading(false);
    sessionIdRef.current = null;
    clearRagSessionId();
    setMessages([]);
    setError(null);
    setLastQuery(null);
  }, []);

  return { messages, loading, error, send, retry, startNewChat };
}
