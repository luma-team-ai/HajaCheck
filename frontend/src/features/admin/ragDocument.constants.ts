import type {
  RagDocumentSourceType,
  RagEmbeddingStatus,
  RagTargetCollection,
} from './ragDocument.types';

// RAG 문서 관리 라벨·배지 스타일 — #22/HAJA-35. success(초록) 토큰이 shared/styles/tokens.css에 없어
// (constants.ts STATUS_DOT_CLASS와 동일 트레이드오프, P3 — 토큰 승격은 후속 이슈) DONE만 AdminUserTable의
// STATUS_DOT_CLASS.ACTIVE와 동일한 hex를 재사용한다. 나머지는 이미 승격된 soft 토큰만 쓴다.
export const SOURCE_TYPE_LABEL: Record<RagDocumentSourceType, string> = {
  LAW: '법규',
  GUIDELINE: '지침',
};

export const TARGET_COLLECTION_LABEL: Record<RagTargetCollection, string> = {
  REGULATIONS: '법규·지침 (regulations)',
  DEFECT_KB: '하자 지식 (defect_kb)',
};

export const EMBEDDING_STATUS_LABEL: Record<RagEmbeddingStatus, string> = {
  PENDING: '대기',
  EMBEDDING: '임베딩 중',
  DONE: '완료',
  FAILED: '실패',
};

export const EMBEDDING_STATUS_DOT_CLASS: Record<RagEmbeddingStatus, string> = {
  PENDING: 'bg-neutral-400',
  EMBEDDING: 'bg-warning-soft-fg',
  DONE: 'bg-[#16a34a]',
  FAILED: 'bg-danger',
};

export const SOURCE_TYPE_OPTIONS = Object.keys(SOURCE_TYPE_LABEL) as RagDocumentSourceType[];
export const TARGET_COLLECTION_OPTIONS = Object.keys(TARGET_COLLECTION_LABEL) as RagTargetCollection[];
