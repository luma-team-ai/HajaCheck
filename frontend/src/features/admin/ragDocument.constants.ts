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

// FAILED를 "실패"가 아니라 "재임베딩 필요"로 표기한다 — 이 화면에서 FAILED 문서에 대한 유일한
// 다음 행동이 재임베딩 버튼이라, 상태 자체를 "그다음 뭘 해야 하는지"로 표현하는 게 더 실용적이다
// (Figma 디자인의 주황 "재임베딩 필요" 라벨과 동일한 의미로 맞춤).
export const EMBEDDING_STATUS_LABEL: Record<RagEmbeddingStatus, string> = {
  PENDING: '대기',
  EMBEDDING: '진행중',
  DONE: '완료',
  FAILED: '재임베딩 필요',
};

export const EMBEDDING_STATUS_DOT_CLASS: Record<RagEmbeddingStatus, string> = {
  PENDING: 'bg-neutral-400',
  EMBEDDING: 'bg-[#2563eb]',
  DONE: 'bg-[#16a34a]',
  FAILED: 'bg-[#f97316]',
};

// 상태 텍스트 색 — PENDING/DONE은 본문 기본색 그대로 두고, EMBEDDING(진행중)·FAILED(재임베딩 필요)만
// 점 색과 맞춰 강조한다(Figma에서 이 둘만 텍스트도 색이 있고 나머지는 회색인 것과 동일).
export const EMBEDDING_STATUS_TEXT_CLASS: Record<RagEmbeddingStatus, string> = {
  PENDING: 'text-text-default',
  EMBEDDING: 'text-[#2563eb]',
  DONE: 'text-text-default',
  FAILED: 'text-[#f97316]',
};

export const SOURCE_TYPE_OPTIONS = Object.keys(SOURCE_TYPE_LABEL) as RagDocumentSourceType[];
export const TARGET_COLLECTION_OPTIONS = Object.keys(TARGET_COLLECTION_LABEL) as RagTargetCollection[];
