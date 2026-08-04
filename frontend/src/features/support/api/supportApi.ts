import { api } from '../../../shared/api/axios';
import { aiClient } from '../../../shared/api/aiClient';
import type {
  ChatSessionMessageResponse,
  ChatSessionResponse,
  RagAnswerData,
  RagChatRequest,
} from '../types';

export const supportApi = {
  // POST /api/ai/rag-chat — 고객지원 AI 어시스턴트(RAG 법규 Q&A).
  // 설계 §7: 공개 경로는 Spring(/api/ai) 경유 — FastAPI(/ai) 직접 호출 금지.
  ragChat: (req: RagChatRequest) => aiClient.post<RagAnswerData>('/rag-chat', req),
  // POST /api/chat-sessions — RAG 대화 세션 생성(설계 §2/§5.1, HAJA-668). aiClient는 baseURL이
  // /api/ai라 이 엔드포인트(/api/chat-sessions, api 다른 prefix)는 공통 axios 인스턴스로 호출한다.
  createSession: () => api.post<ChatSessionResponse>('/chat-sessions', { sessionType: 'RAG' }),
  // GET /api/chat-sessions/{sessionId}/messages — 세션 이력 조회(새로고침 시 대화 복원용).
  // 세션 미소유/미존재 시 403 — 호출부(useRagChat)가 catch해서 clearRagSessionId + 빈 상태로 처리.
  getSessionMessages: (sessionId: number) =>
    api.get<ChatSessionMessageResponse[]>(`/chat-sessions/${sessionId}/messages`),
};
