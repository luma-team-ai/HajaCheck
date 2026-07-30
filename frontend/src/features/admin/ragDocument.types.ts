// 관리자 > RAG 문서 관리 도메인 타입 — #22/HAJA-35(GitHub #22, Jira HAJA-35), PRD FR-8-B.
// 백엔드 enum(com.hajacheck.core.rag.entity.*)과 값이 1:1 일치해야 한다 — 한쪽만 바뀌면 라벨/필터가 어긋난다.
export type RagDocumentSourceType = 'LAW' | 'GUIDELINE';
export type RagTargetCollection = 'REGULATIONS' | 'DEFECT_KB';
export type RagEmbeddingStatus = 'PENDING' | 'EMBEDDING' | 'DONE' | 'FAILED';
export type RagDocumentVerificationStatus = 'UNVERIFIED' | 'VERIFIED';

/** GET /api/admin/rag-documents 목록 행 1건 — RagDocumentResponse(Java)와 1:1 대응. */
export interface RagDocument {
  id: number;
  title: string;
  sourceType: RagDocumentSourceType;
  targetCollection: RagTargetCollection;
  /** LAW 문서만 채워짐(ISO date, "2026-01-01") */
  effectiveDate: string | null;
  publisher: string | null;
  /** defect_kb 문서 대상 */
  authoredAt: string | null;
  verificationStatus: RagDocumentVerificationStatus | null;
  embeddingStatus: RagEmbeddingStatus;
  chunkCount: number | null;
  /** 임베딩 완료 시각(ISO, DONE 상태에서만 존재) */
  embeddedAt: string | null;
  createdAt: string;
}

/** POST /api/admin/rag-documents 업로드 폼 입력 — multipart/form-data로 변환해 전송한다. */
export interface RagDocumentUploadPayload {
  file: File;
  title: string;
  sourceType: RagDocumentSourceType;
  targetCollection: RagTargetCollection;
  effectiveDate?: string;
  publisher?: string;
  authoredAt?: string;
}
