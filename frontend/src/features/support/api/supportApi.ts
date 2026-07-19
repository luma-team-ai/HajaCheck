import { aiClient } from '../../../shared/api/aiClient';
import type { RagAnswerData, RagChatRequest } from '../types';

export const supportApi = {
  // POST /api/ai/rag-chat — 고객지원 AI 어시스턴트(RAG 법규 Q&A).
  // 설계 §7: 공개 경로는 Spring(/api/ai) 경유 — FastAPI(/ai) 직접 호출 금지.
  ragChat: (req: RagChatRequest) => aiClient.post<RagAnswerData>('/rag-chat', req),
};
