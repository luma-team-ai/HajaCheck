import { useCallback, useRef, useState } from 'react';
import type { ApiError } from '../../../shared/api/types';
import { supportApi } from '../api/supportApi';
import type { ChatMessage } from '../types';

let idSeq = 0;
function nextId() {
  idSeq += 1;
  return `msg-${Date.now()}-${idSeq}`;
}

// 검색 0건 안내 문구 — 목(support.mock)과 이 훅의 RAG_NO_RESULT 분기가 공유(단일 출처).
export const RAG_NO_RESULT_TEXT = '관련 근거를 찾지 못했습니다.';

function isApiError(err: unknown): err is ApiError {
  return typeof err === 'object' && err !== null && 'code' in err && 'message' in err;
}

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
      setLoading(false);
      inFlightRef.current = false;
    }
  }, []);

  // 실제로 전송을 시작했으면 true 반환 — 호출부가 입력창 clear 여부를 판단(전송 중 타이핑 유실 방지).
  const send = useCallback(
    (query: string): boolean => {
      const trimmed = query.trim();
      if (!trimmed || inFlightRef.current) return false;
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

  return { messages, loading, error, send, retry };
}
