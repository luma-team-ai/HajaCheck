import { useCallback, useState } from 'react';
import type { ApiError } from '../../../shared/api/types';
import { supportApi } from '../api/supportApi';
import type { ChatMessage } from '../types';

let idSeq = 0;
function nextId() {
  idSeq += 1;
  return `msg-${Date.now()}-${idSeq}`;
}

// 고객지원 AI 어시스턴트(RAG 법규 Q&A) 채팅 상태 훅 — dev-08-01 / HAJA-32 / FR-6.
// 메시지 누적 + 로딩/에러 관리. 실 호출은 supportApi.ragChat(설계 §7 /api/ai/rag-chat) 경유.
export function useRagChat() {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [lastQuery, setLastQuery] = useState<string | null>(null);

  const runQuery = useCallback(async (query: string) => {
    setError(null);
    setLoading(true);
    try {
      const res = await supportApi.ragChat({ query });
      setMessages((prev) => [
        ...prev,
        { id: nextId(), role: 'assistant', text: res.data.answer, sources: res.data.sources },
      ]);
    } catch (err) {
      // aiClient 인터셉터가 success:false·네트워크 오류를 ApiError로 reject.
      // (검색 0건은 설계 §4.3대로 목에서 정상 응답 + 빈 sources로 내려와 에러가 아니다.)
      setError(err as ApiError);
    } finally {
      setLoading(false);
    }
  }, []);

  const send = useCallback(
    (query: string) => {
      const trimmed = query.trim();
      if (!trimmed || loading) return;
      setMessages((prev) => [...prev, { id: nextId(), role: 'user', text: trimmed }]);
      setLastQuery(trimmed);
      void runQuery(trimmed);
    },
    [loading, runQuery],
  );

  // 에러 후 재시도 — 마지막 질의를 사용자 말풍선 중복 없이 다시 호출
  const retry = useCallback(() => {
    if (lastQuery && !loading) void runQuery(lastQuery);
  }, [lastQuery, loading, runQuery]);

  return { messages, loading, error, send, retry };
}
