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

// 마운트 복원(GET)을 질의가 기다려 주는 상한(#1590). 복원은 보통 수십 ms 안에 끝나므로 이 상한에
// 걸리는 것은 이례적인 지연뿐이고, 그때는 기다리지 않고 새 세션으로 질의를 진행한다(질의 자체가
// 복원 때문에 막히면 안 된다).
//
// #1598에서 api 인스턴스에 전역 timeout(30초)이 생겼지만 이 상한은 **그대로 남긴다** — 둘은 막는
// 대상이 다르다. 전역 timeout은 "요청이 영원히 안 끝나는 것"을 막는 장애 방어이고, 이 3초는
// "복원이 끝날 때까지 사용자의 첫 질문을 얼마나 붙잡아 둘 것인가"를 정하는 UX 예산이다. 전역
// 상한만 있으면 복원이 정상적으로(그러나 느리게) 25초 걸릴 때 사용자의 질문이 25초 동안 막힌다 —
// 전역 30초는 그 경우를 전혀 줄여주지 못한다. 그래서 이 상한을 지우면 안 된다.
export const RAG_RESTORE_WAIT_TIMEOUT_MS = 3000;

/** promise가 ms 안에 끝나지 않으면 그대로 진행한다(타이머는 항상 해제 — 테스트 잔여 타이머 방지). */
function waitAtMost(promise: Promise<void>, ms: number): Promise<void> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  return Promise.race([
    promise,
    new Promise<void>((resolve) => {
      timer = setTimeout(resolve, ms);
    }),
  ]).finally(() => clearTimeout(timer));
}

// 세션 이력 조회(ChatSessionMessageResponse[])를 화면 표시용 ChatMessage[]로 매핑한다(HAJA-668).
// 필드명 차이: 백엔드 sender(USER/BOT/COUNSELOR)→role(user/assistant), citations는 SourceCitation과
// 형태가 다르다(documentId→doc_id, chunkRef→chunk_ref). title/collection은 #1698로 세션 이력
// 응답에도 추가됐다 — 과거엔 없어서 snippet(청크 원문)을 title 대용으로 썼는데, 그게 재진입 시
// 참고문서 칩에 청크 원문이 그대로 노출되던 버그였다(#1700).
//
// title 폴백은 두 갈래다(구분 유지 — 서로 다른 원인):
// ① title === null: 인용된 문서가 삭제됨(#1597, FK ON DELETE SET NULL로 documentId/title/collection이
//    전부 null) → "삭제된 문서"로 표시. locator/snippet은 citation 행 자체 보관이라 그대로 남는다.
// ② title === ''(문서는 존재하는데 title 필드만 빈 값인 방어적 예외, #1700 확정 계약) →
//    `문서 #{documentId}`로 폴백. 이 갈래는 documentId가 항상 non-null이다(①과 동시에 일어날 수 없음).
// snippet은 title 대용이 아니라 칩 펼침 시 미리보기 전용.
function toChatMessage(msg: ChatSessionMessageResponse): ChatMessage {
  const sources: SourceCitation[] = msg.citations.map((c) => ({
    // documentId는 백엔드 Long(JSON number)이라 SourceCitation.doc_id 계약(문자열)에 맞춰 변환한다
    // (PR #1563 P2 픽스 — 이전엔 number를 그대로 문자열 필드에 넣어 런타임 타입이 계약과 어긋났다).
    // 문서가 삭제됐으면(documentId=null) 참조할 문서가 없으므로 빈 문자열로 둔다(#1597) — 링크/상세
    // 이동 대상이 아니라 렌더에는 쓰이지 않는다(SourceChip은 title/locator/snippet만 표시).
    doc_id: c.documentId === null ? '' : String(c.documentId),
    title: c.title === null ? '삭제된 문서' : c.title || `문서 #${c.documentId}`,
    collection: c.collection,
    locator: c.locator,
    chunk_ref: c.chunkRef,
    snippet: c.snippet,
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
  // 마운트 복원(GET)의 진행 상태(#1590) — runQuery가 새 세션을 발급하기 전에 이걸 기다린다.
  // 기다리지 않으면 ① 계정 전환 시 옛 세션으로 질의해 403(이 이슈의 출발점) ② 동일 사용자인데도
  // 복원 전에 새 세션이 발급돼 대화가 갈라지고 옛 세션이 고아가 된다.
  const restoreRef = useRef<Promise<void> | null>(null);
  // runQuery가 세션을 확정(새로 발급)했는지 — 복원이 상한을 넘겨 뒤늦게 도착했을 때 이미 쓰고 있는
  // 세션을 옛 세션으로 되돌리거나(200) 지우지(403) 않기 위한 가드.
  const sessionCommittedRef = useRef(false);

  // 마운트 시 1회: localStorage에 세션이 있으면 이력을 복원한다(설계 §2 "유지·복원").
  useEffect(() => {
    const savedId = getRagSessionId();
    if (savedId === null) return;
    // 주의(#1590 P2): 여기서 sessionIdRef를 먼저 채우면 안 된다 — 복원 GET 응답이 오기 전에
    // 사용자가 첫 질문을 보내면 (계정이 바뀐 경우) 이전 사용자의 session_id로 질의가 나가
    // 소유자 검증 403으로 실패한다. 소유권이 확인되는 시점 = 복원 GET 응답 이후로 미룬다.
    // 대신 runQuery가 이 promise(restoreRef)를 기다리므로, 동일 사용자면 옛 세션을 그대로 이어간다.

    let cancelled = false;
    const restoring = (async () => {
      try {
        const res = await supportApi.getSessionMessages(savedId);
        if (cancelled) return;
        // 200 = 이 세션이 현재 사용자 소유임이 확인된 것 → 채택한다. 사용자가 그 사이 send()를
        // 했더라도 runQuery는 여기(restoreRef)를 기다리므로 아직 새 세션을 발급하지 않았고,
        // 방금 보낸 질문도 이 세션에 이어 붙는다(설계 §2 "유지·복원" 계약).
        // 예외: 복원이 상한을 넘겨 runQuery가 이미 새 세션을 발급했다면(sessionCommittedRef)
        // 그 세션을 유지한다 — 여기서 되돌리면 대화가 두 세션으로 갈라진다.
        if (!sessionCommittedRef.current) sessionIdRef.current = savedId;
        // 그 사이 사용자가 이미 새 메시지를 보냈으면(PR #1563 P2 픽스) 복원으로 화면을 덮어쓰지
        // 않는다 — 같은 세션에 이어지므로 방금 보낸 대화는 다음 새로고침에서 함께 복원된다.
        if (hasSentRef.current) return;
        setMessages(res.data.map(toChatMessage));
      } catch {
        // 세션 만료/삭제 등(403 등) — 에러 화면 대신 조용히 새 대화로 취급(설계 §2 지시).
        // 상한 초과로 이미 새 세션이 발급된 뒤라면 그 세션을 지우면 안 된다(#1590).
        if (cancelled || sessionCommittedRef.current) return;
        sessionIdRef.current = null;
        clearRagSessionId();
      }
    })();
    // 복원이 끝나면 대기 대상에서 뺀다 — 남겨두면 이후 질의가 매번 이 promise를 다시 await 한다.
    restoreRef.current = restoring.finally(() => {
      restoreRef.current = null;
    });

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
      // 마운트 복원(GET)이 진행 중이면 먼저 기다린다(#1590) — 응답 전에 세션을 새로 발급하면
      // 같은 사용자인데도 대화가 두 세션으로 갈라지고 저장돼 있던 세션은 고아가 된다.
      // 복원이 비정상적으로 지연되면 질의를 막지 않고 새 세션 경로로 진행한다(상한).
      if (restoreRef.current) {
        await waitAtMost(restoreRef.current, RAG_RESTORE_WAIT_TIMEOUT_MS);
        // 기다림은 1회로 끝낸다 — 여기서 비우지 않으면 복원이 느린 동안 이후 질의가 매번 상한만큼
        // 지연된다. (#1598 이전에는 "복원 GET이 영원히 응답하지 않으면 위 finally가 실행되지
        // 않는다"는 게 주된 근거였지만, 이제 전역 timeout이 그 요청을 30초 안에 정리하므로
        // restoreRef는 결국 비워진다. 그래도 이 줄은 필요하다 — 그 30초 동안 도착하는 질의들이
        // 매번 3초씩 헛되이 기다리는 것을 막는다.)
        restoreRef.current = null;
        if (gen !== genRef.current) return; // 그 사이 startNewChat — 이 결과는 폐기
      }

      // 최초 질의(세션 없음)면 먼저 세션을 생성해 session_id를 확보한다(설계 §2 "생성 시점").
      let sessionId = sessionIdRef.current;
      if (sessionId === null) {
        // createSession await 도중 늦게 도착한 복원 응답이 이 세션을 덮어쓰지 않도록 먼저 표시한다.
        sessionCommittedRef.current = true;
        let sessionRes;
        try {
          sessionRes = await supportApi.createSession();
        } catch (createError) {
          // 발급에 실패했으면 "확정"이 아니라 "시도"였을 뿐이다 — 되돌리지 않으면 이후 도착한
          // 복원 200이 저장된 세션을 채택하지 못하고(대화 분기), 403도 무효한 id를 지우지 못한 채
          // localStorage에 남는다(#1590 리뷰 P3).
          sessionCommittedRef.current = false;
          throw createError;
        }
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
    // 진행 중이던 마운트 복원이 뒤늦게 200으로 도착해 "새 대화"에 옛 세션을 되살리지 못하게
    // 끊는다(#1590) — 대기 대상에서 제외 + 복원의 세션 조작 차단.
    restoreRef.current = null;
    sessionCommittedRef.current = true;
    setMessages([]);
    setError(null);
    setLastQuery(null);
  }, []);

  return { messages, loading, error, send, retry, startNewChat };
}
