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

// 요청 스키마 — session_id 등 세션·이력 연동은 설계 §9 확정 후 확장 예정
export interface RagChatRequest {
  query: string;
}

// 채팅 화면 로컬 메시지 모델(표시용 — 서버 스키마와 별개)
export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  text: string;
  sources?: SourceCitation[];
}
