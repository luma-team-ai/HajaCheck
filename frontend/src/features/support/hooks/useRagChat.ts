import { useCallback, useRef, useState } from 'react';
import type { ApiError } from '../../../shared/api/types';
import { supportApi } from '../api/supportApi';
import type { ChatMessage } from '../types';

let idSeq = 0;
function nextId() {
  idSeq += 1;
  return `msg-${Date.now()}-${idSeq}`;
}

const NO_RESULT_TEXT = '관련 근거를 찾지 못했습니다.';

// 고객지원 AI 어시스턴트(RAG 법규 Q&A) 채팅 상태 훅 — dev-08-01 / HAJA-32 / FR-6.
// 메시지 누적 + 로딩/에러 관리. 실 호출은 supportApi.ragChat(설계 §7 /api/ai/rag-chat) 경유.
export function useRagChat() {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [lastQuery, setLastQuery] = useState<string | null>(null);
  // 인플라이트 가드 — loading은 비동기 state라 빠른 연속 호출에서 중복 요청을 못 막는다.
  // ref로 동기 차단해 send/retry 이중 발화를 막는다.
  const inFlightRef = useRef(false);

  const runQuery = useCallback(async (query: string) => {
    inFlightRef.current = true;
    setError(null);
    setLoading(true);
    try {
      const res = await supportApi.ragChat({ query });
      setMessages((prev) => [
        ...prev,
        { id: nextId(), role: 'assistant', text: res.data.answer, sources: res.data.sources },
      ]);
    } catch (err) {
      const apiError = err as ApiError;
      // 방어(설계 §9 계약 확정 전): 백엔드가 검색 0건을 success:false(RAG_NO_RESULT)로 주더라도
      // 에러가 아니라 "근거 없음" 안내 메시지로 표시한다. 0건을 정상 응답(빈 sources)으로 주는
      // 경우와 양쪽 모두 안전하게 동작한다.
      if (apiError?.code === 'RAG_NO_RESULT') {
        setMessages((prev) => [
          ...prev,
          { id: nextId(), role: 'assistant', text: NO_RESULT_TEXT, sources: [] },
        ]);
      } else {
        // 그 외(네트워크·LLM 오류 등)만 에러 UI로
        setError(apiError);
      }
    } finally {
      setLoading(false);
      inFlightRef.current = false;
    }
  }, []);

  const send = useCallback(
    (query: string) => {
      const trimmed = query.trim();
      if (!trimmed || inFlightRef.current) return;
      setMessages((prev) => [...prev, { id: nextId(), role: 'user', text: trimmed }]);
      setLastQuery(trimmed);
      void runQuery(trimmed);
    },
    [runQuery],
  );

  // 에러 후 재시도 — 마지막 질의를 사용자 말풍선 중복 없이 다시 호출
  const retry = useCallback(() => {
    if (lastQuery && !inFlightRef.current) void runQuery(lastQuery);
  }, [lastQuery, runQuery]);

  return { messages, loading, error, send, retry };
}
