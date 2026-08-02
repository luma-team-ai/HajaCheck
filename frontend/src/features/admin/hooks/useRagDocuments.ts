import { useQuery } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { ragDocumentApi } from '../api/ragDocumentApi';
import type { RagDocument } from '../ragDocument.types';

// RAG 문서 목록 조회 — 임베딩 완료 확정이 백엔드 폴러(RagEmbeddingCompletionPoller, #1328)를 거쳐
// 비동기로 반영되므로, mutation 성공 시 invalidateQueries만으로는 EMBEDDING→DONE/FAILED 전이를
// 놓친다. 목록에 EMBEDDING 상태 문서가 남아있는 동안만 짧은 간격으로 자동 재조회하고, 없으면
// 폴링을 멈춘다(불필요한 요청 방지).
const EMBEDDING_POLL_INTERVAL_MS = 4000;

export function useRagDocuments() {
  return useQuery<RagDocument[], ApiError>({
    queryKey: ['admin', 'rag-documents'],
    queryFn: () => ragDocumentApi.list().then((res) => res.data),
    refetchInterval: (query) => {
      const documents = query.state.data;
      const hasEmbeddingInProgress = documents?.some((doc) => doc.embeddingStatus === 'EMBEDDING');
      return hasEmbeddingInProgress ? EMBEDDING_POLL_INTERVAL_MS : false;
    },
  });
}
