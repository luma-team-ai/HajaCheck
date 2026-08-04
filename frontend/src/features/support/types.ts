// 고객지원 AI 어시스턴트(RAG 법규 Q&A) 타입 — dev-08-01 / HAJA-32 / FR-6
// 스키마 SoT: docs/design/ai/rag_chatbot_design.md §2 + ai-server ai/core/schemas.py(HAJA-145)
// ⚠️ 재정의 금지 — 설계 §2의 SourceCitation/RagAnswerData를 그대로 미러. 필드는 wire(snake_case) 그대로 둔다.

export interface SourceCitation {
  doc_id: string; // 양의 정수 문자열(^[1-9][0-9]*$)
  title: string; // Chroma metadata `source` → API 경계에서 title
  collection: 'regulations' | 'defect_kb';
  locator: string; // 렌더 완료 문구("제12조" / "제12조 ①" / "12페이지") — FE 재조립 금지(설계 §5·§7)
  chunk_ref: string; // Chroma document id({doc_id}_{chunk_index})
}

export interface RagAnswerData {
  answer: string;
  sources: SourceCitation[];
}

// 요청 스키마 — sessionId는 HAJA-668(#1548, 설계 §2/§5.1)로 확정. 없으면 기존처럼 단발 질의.
// 백엔드 RagChatRequest(record)는 Jackson 기본 camelCase 매핑이라 sessionId로 보내야 한다
// (snake_case로 보내면 백엔드가 null로 받아 세션이 전혀 연결되지 않는다 — #1548 로컬 검증 중 발견).
export interface RagChatRequest {
  query: string;
  sessionId?: number;
}

// 세션 라이프사이클(설계 §2/§5.1, HAJA-668) — Spring `ChatSessionController` 응답을 그대로 미러.
// Jackson 기본 camelCase 응답이라 wire 그대로 camelCase로 둔다(RagAnswerData의 snake_case와는 별개 계약).
export interface ChatSessionResponse {
  sessionId: number;
  sessionType: string;
  startedAt: string;
}

export interface ChatSessionCitation {
  documentId: string;
  chunkRef: string;
  locator: string;
  snippet: string;
}

export interface ChatSessionMessageResponse {
  id: number;
  sessionId: number;
  sender: 'USER' | 'BOT' | 'COUNSELOR';
  content: string;
  citations: ChatSessionCitation[];
  createdAt: string;
}

// 채팅 화면 로컬 메시지 모델(표시용 — 서버 스키마와 별개)
export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  text: string;
  sources?: SourceCitation[];
}
